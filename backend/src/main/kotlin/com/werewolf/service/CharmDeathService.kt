package com.werewolf.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.phase.HardModeCounterplay
import com.werewolf.game.phase.WinCheckTrigger
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.game.role.RoleRules
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import org.springframework.stereotype.Service

data class CharmOutcome(val victimUserId: String, val winner: WinnerSide?)

/** 狼美人 殉情 when the Wolf Beauty leaves by day (W6, W11, W15). Callers wire it into each day exit. */
@Service
class CharmDeathService(
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val actionLogService: ActionLogService,
    private val stompPublisher: StompPublisher,
    private val winConditionChecker: WinConditionChecker,
) {
    /**
     * Kill the player [outUserId]'s charm takes along, record and broadcast it, then run the
     * wipe-only win check (W15). Null = no 殉情. Call inside the caller's transaction.
     */
    fun onDayExit(context: GameContext, outUserId: String): CharmOutcome? {
        val game = context.game
        val todaysNight = context.allNightPhases.firstOrNull { it.dayNumber == game.dayNumber }
        val victimId = RoleRules.charmVictim(outUserId, context.players, todaysNight) ?: return null

        // Fresh rows: the caller may or may not have saved the beauty dead yet.
        val rows = gamePlayerRepository.findByGameId(context.gameId)
        val victim = rows.first { it.userId == victimId }
        victim.alive = false
        victim.diedDay = game.dayNumber
        gamePlayerRepository.save(victim)
        game.charmDeathUserId = victimId
        gameRepository.save(game)
        actionLogService.recordCharmDeath(context.gameId, game.dayNumber, outUserId, victimId)
        stompPublisher.broadcastGameAfterCommit(context.gameId, DomainEvent.PlayerCharmDeath(context.gameId, outUserId, victimId))

        // The beauty still counts as alive for the check, even if its row is already dead.
        val alive = rows.filter { it.userId != victimId && (it.alive || it.role == PlayerRole.WOLF_BEAUTY) }
        val winner = winConditionChecker.check(
            alivePlayers = alive,
            mode = context.room.winCondition,
            trigger = WinCheckTrigger.POST_NIGHT,
            counterplay = HardModeCounterplay.of(alive, context.allNightPhases, knightDuelUsed = game.knightDuelTargetUserId != null),
            roster = rows,
        )
        return CharmOutcome(victimId, winner)
    }
}
