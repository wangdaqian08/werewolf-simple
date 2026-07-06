package com.werewolf.service

import com.werewolf.model.PerkActivationStatus
import com.werewolf.repository.GameRepository
import com.werewolf.repository.PerkActivationRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

/**
 * Safety net for perk activations whose room never started a game: there is
 * no /api/room/leave or room-disband endpoint, so an abandoned WAITING room
 * would otherwise strand the player's credits forever. Any activation still
 * ACTIVE with no bound game after [STALE_AFTER_HOURS] is refunded.
 *
 * Activations bound to an IN-FLIGHT game are never touched here — they are
 * settled (CONSUMED/REFUNDED) by PerkSettlementService at game end. A second
 * pass, [settleLeftoversForEndedGames], self-heals any still-live activation
 * bound to an already-ended game (pre-deploy rows or a missed settle path).
 */
@Component
class PerkRefundSweep(
    private val perkActivationRepository: PerkActivationRepository,
    private val perkService: PerkService,
    private val perkSettlementService: PerkSettlementService,
    private val gameRepository: GameRepository,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(PerkRefundSweep::class.java)

    // One transaction per refund so a poisoned row can't block the sweep
    // (same pattern as OrphanedGameRecovery).
    private val txTemplate = TransactionTemplate(txManager)

    @Scheduled(fixedDelayString = "\${werewolf.perks.sweep-interval-ms:3600000}")
    fun refundStaleActivations() {
        val cutoff = LocalDateTime.now().minusHours(STALE_AFTER_HOURS)
        val stale = perkActivationRepository
            .findByStatusAndGameIdIsNullAndCreatedAtBefore(PerkActivationStatus.ACTIVE, cutoff)
        if (stale.isEmpty()) return

        log.info("[perk-sweep] refunding {} stale activations (never-started rooms)", stale.size)
        stale.forEach { activation ->
            try {
                txTemplate.execute {
                    perkService.refundActiveForUser(activation.roomId, activation.userId)
                }
            } catch (e: Exception) {
                log.error("[perk-sweep] failed to refund activation id={}", activation.id, e)
            }
        }
    }

    /**
     * Self-heal pass: settles activations still ACTIVE/VOID whose bound game
     * has already ended (fires at startup too — fixedDelay schedules run
     * immediately). Per-activation idempotency lives in PerkSettlementService,
     * so racing a concurrent game-end settle is harmless.
     */
    @Scheduled(fixedDelayString = "\${werewolf.perks.sweep-interval-ms:3600000}")
    fun settleLeftoversForEndedGames() {
        val leftovers = perkActivationRepository.findUnsettledForEndedGames()
        if (leftovers.isEmpty()) return

        log.info("[perk-sweep] settling {} leftover activations bound to ended games", leftovers.size)
        leftovers.mapNotNull { it.gameId }.distinct().forEach { gameId ->
            try {
                txTemplate.execute {
                    val game = gameRepository.findById(gameId).orElse(null) ?: return@execute null
                    perkSettlementService.settleForGame(gameId, cancelled = game.winner == null)
                }
            } catch (e: Exception) {
                log.error("[perk-sweep] failed to settle leftovers for game {}", gameId, e)
            }
        }
    }

    companion object {
        const val STALE_AFTER_HOURS = 24L
    }
}
