package com.werewolf.service

import com.werewolf.repository.CreditTransactionRepository
import com.werewolf.repository.EliminationHistoryRepository
import com.werewolf.repository.GameEventRepository
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.GameSettlementRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.repository.PerkActivationRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.SheriffCandidateRepository
import com.werewolf.repository.SheriffElectionRepository
import com.werewolf.repository.VoteRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * e2e-only data cleanup for integration test runs. Deletes every row a test
 * room left behind (room, players, games and all game-scoped children) so a
 * long-lived locally-reused backend doesn't accumulate state between runs.
 * Users and wallets are deliberately KEPT — guest identities ('Host', bots)
 * are shared across rooms within a session, and purchase transactions with
 * no game/activation linkage are wallet history, not game state.
 *
 * Gated to the e2e profile (and never alongside prod) exactly like the
 * DevAuthController hardening — see TestSupportControllerProfileTest.
 */
@Service
@Profile("e2e & !prod")
class TestSupportService(
    private val roomRepository: RoomRepository,
    private val roomPlayerRepository: RoomPlayerRepository,
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val nightPhaseRepository: NightPhaseRepository,
    private val sheriffElectionRepository: SheriffElectionRepository,
    private val sheriffCandidateRepository: SheriffCandidateRepository,
    private val voteRepository: VoteRepository,
    private val gameEventRepository: GameEventRepository,
    private val eliminationHistoryRepository: EliminationHistoryRepository,
    private val perkActivationRepository: PerkActivationRepository,
    private val creditTransactionRepository: CreditTransactionRepository,
    private val gameSettlementRepository: GameSettlementRepository,
) {

    private val log = LoggerFactory.getLogger(TestSupportService::class.java)

    /**
     * Delete every room matching [roomCode] (codes recycle, so a test code
     * can map to several finished rooms) together with its full game graph,
     * children first. Returns per-table deletion counts, or null when no
     * room matches — the controller maps that to 404.
     */
    @Transactional
    fun deleteRoomCascade(roomCode: String): Map<String, Int>? {
        val rooms = roomRepository.findAllByRoomCode(roomCode)
        if (rooms.isEmpty()) return null

        val counts = linkedMapOf<String, Int>()
        fun add(table: String, n: Long) {
            counts[table] = (counts[table] ?: 0) + n.toInt()
        }

        for (room in rooms) {
            val roomId = room.roomId ?: continue
            val games = gameRepository.findByRoomId(roomId)
            val gameIds = games.mapNotNull { it.gameId }
            val activationIds = perkActivationRepository.findByRoomId(roomId).mapNotNull { it.id }

            // Children first — the e2e H2 schema has no FK constraints, but
            // the same order must hold on a Flyway-provisioned Postgres.
            if (activationIds.isNotEmpty()) {
                add(
                    "credit_transactions",
                    creditTransactionRepository.deleteByPerkActivationIdIn(activationIds),
                )
            }
            if (gameIds.isNotEmpty()) {
                add("credit_transactions", creditTransactionRepository.deleteByGameIdIn(gameIds))
                add("game_settlements", gameSettlementRepository.deleteByGameIdIn(gameIds))
                add("votes", voteRepository.deleteByGameIdIn(gameIds))
                add("game_events", gameEventRepository.deleteByGameIdIn(gameIds))
                add("elimination_history", eliminationHistoryRepository.deleteByGameIdIn(gameIds))
                val electionIds = sheriffElectionRepository.findByGameIdIn(gameIds).mapNotNull { it.id }
                if (electionIds.isNotEmpty()) {
                    add("sheriff_candidates", sheriffCandidateRepository.deleteByElectionIdIn(electionIds))
                }
                add("sheriff_elections", sheriffElectionRepository.deleteByGameIdIn(gameIds))
                add("night_phases", nightPhaseRepository.deleteByGameIdIn(gameIds))
                add("game_players", gamePlayerRepository.deleteByGameIdIn(gameIds))
            }
            add("perk_activations", perkActivationRepository.deleteByRoomId(roomId))
            gameRepository.deleteAll(games)
            add("games", games.size.toLong())
            add("room_players", roomPlayerRepository.deleteByRoomId(roomId))
            roomRepository.delete(room)
            add("rooms", 1)
        }

        log.info("[TestSupport] deleteRoomCascade code={} -> {}", roomCode, counts)
        return counts
    }
}
