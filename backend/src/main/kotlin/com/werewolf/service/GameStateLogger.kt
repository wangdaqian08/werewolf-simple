package com.werewolf.service

import com.werewolf.model.*
import com.werewolf.repository.*
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

/**
 * Emits a one-line snapshot of a game's current state for server-side debugging.
 *
 * Goal: `grep "game.state game=N" backend.log` walks every state transition for
 * one game in chronological order, so when a game gets stuck the last line tells
 * you the phase, sub-phase, and (load-bearing) which player(s) the game is
 * waiting on. Combined with the per-action logs (`action.submit ...`) added in
 * GameController, the log alone answers "what was done" and "where it's stuck".
 *
 * Triggered automatically from StompPublisher whenever a state-change DomainEvent
 * is broadcast (one site for the whole codebase). Runs on the shared `taskExecutor`
 * (see AsyncConfig) so the extra DB reads never delay the STOMP broadcast path.
 */
@Service
class GameStateLogger(
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val nightPhaseRepository: NightPhaseRepository,
    private val sheriffElectionRepository: SheriffElectionRepository,
    private val voteRepository: VoteRepository,
) {
    private val log = LoggerFactory.getLogger(GameStateLogger::class.java)

    /**
     * Emit a snapshot. `context` is a short label describing what just changed
     * (e.g. "PHASE=NIGHT/-", "NIGHT_SUBPHASE=WITCH_ACT", "GAME_OVER winner=VILLAGER").
     *
     * `@Async` — the game-logic thread fires-and-forgets; all DB reads below run
     * on the async executor. Reorders in the log are possible if multiple snapshots
     * queue for the same game, but sub-second-scale log reordering is acceptable
     * for debugging (each line still carries its context label).
     */
    @Async
    fun logSnapshot(gameId: Int, context: String) {
        try {
            val game = gameRepository.findById(gameId).orElse(null)
            if (game == null) {
                log.warn("game.state game={} ctx={} -> SKIP (game not found)", gameId, context)
                return
            }

            val players = gamePlayerRepository.findByGameId(gameId)
            val alive = players.count { it.alive }
            val sheriff = players.firstOrNull { it.sheriff }?.userId

            val sub = subPhaseLabel(gameId, game)
            val details = phaseDetails(gameId, game, players)
            val waitingOn = computeWaitingOn(gameId, game, players)

            log.info(
                "game.state game={} ctx={} phase={} subPhase={} day={} alive={}/{} sheriff={} {} waitingOn=[{}]",
                gameId, context, game.phase, sub, game.dayNumber,
                alive, players.size, sheriff ?: "null", details,
                waitingOn.joinToString(","),
            )
        } catch (e: Exception) {
            log.warn("game.state game={} ctx={} -> SKIP (logger error: {})", gameId, context, e.message)
        }
    }

    private fun subPhaseLabel(gameId: Int, game: Game): String = when (game.phase) {
        GamePhase.NIGHT -> nightPhaseRepository
            .findByGameIdAndDayNumber(gameId, game.dayNumber)
            .orElse(null)?.subPhase?.name ?: "?"
        GamePhase.SHERIFF_ELECTION -> sheriffElectionRepository
            .findByGameId(gameId)
            .orElse(null)?.subPhase?.name ?: "?"
        else -> game.subPhase ?: "-"
    }

    private fun phaseDetails(gameId: Int, game: Game, players: List<GamePlayer>): String = when (game.phase) {
        GamePhase.NIGHT -> {
            val np = nightPhaseRepository
                .findByGameIdAndDayNumber(gameId, game.dayNumber)
                .orElse(null)
            if (np == null) "" else "wolfTarget=${np.wolfTargetUserId ?: "null"}" +
                " seerChecked=${np.seerCheckedUserId ?: "null"}" +
                " witchAnti=${np.witchAntidoteUsed}" +
                " witchPoison=${np.witchPoisonTargetUserId ?: "null"}" +
                " guardTarget=${np.guardTargetUserId ?: "null"}"
        }
        GamePhase.DAY_VOTING -> {
            val votes = voteRepository.findByGameIdAndVoteContextAndDayNumber(
                gameId, VoteContext.ELIMINATION, game.dayNumber,
            )
            val aliveCount = players.count { it.alive }
            "votes=${votes.size}/$aliveCount"
        }
        else -> ""
    }

    /**
     * Best-effort "who is the game waiting on" derivation. Returns userIds (not
     * nicknames — keep log compact and stable). Empty list means no actionable
     * blocker (typically auto-advancing or game over).
     */
    private fun computeWaitingOn(gameId: Int, game: Game, players: List<GamePlayer>): List<String> {
        val alive = players.filter { it.alive }
        return when (game.phase) {
            GamePhase.ROLE_REVEAL -> alive.filter { !it.confirmedRole }.map { it.userId }
            GamePhase.WAITING -> listOf("host:${game.hostUserId}")
            GamePhase.SHERIFF_ELECTION -> sheriffWaitingOn(gameId, game, alive)
            GamePhase.NIGHT -> nightWaitingOn(gameId, game, alive)
            GamePhase.DAY_PENDING -> listOf("host:${game.hostUserId}")
            GamePhase.DAY_DISCUSSION -> listOf("host:${game.hostUserId}")
            GamePhase.DAY_VOTING -> votingWaitingOn(gameId, game, alive)
            GamePhase.GAME_OVER -> emptyList()
        }
    }

    private fun nightWaitingOn(gameId: Int, game: Game, alivePlayers: List<GamePlayer>): List<String> {
        val np = nightPhaseRepository
            .findByGameIdAndDayNumber(gameId, game.dayNumber)
            .orElse(null) ?: return emptyList()
        return when (np.subPhase) {
            NightSubPhase.WAITING, NightSubPhase.COMPLETE -> emptyList()  // auto-advance
            NightSubPhase.WEREWOLF_PICK -> alivePlayers.filter { it.role == PlayerRole.WEREWOLF }.map { it.userId }
            NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT ->
                alivePlayers.filter { it.role == PlayerRole.SEER }.map { it.userId }
            NightSubPhase.WITCH_ACT -> alivePlayers.filter { it.role == PlayerRole.WITCH }.map { it.userId }
            NightSubPhase.GUARD_PICK -> alivePlayers.filter { it.role == PlayerRole.GUARD }.map { it.userId }
        }
    }

    private fun votingWaitingOn(gameId: Int, game: Game, alivePlayers: List<GamePlayer>): List<String> {
        return when (game.subPhase) {
            // VOTING / RE_VOTING: alive players who haven't cast a vote this round
            "VOTING", "RE_VOTING" -> {
                val voted = voteRepository.findByGameIdAndVoteContextAndDayNumber(
                    gameId, VoteContext.ELIMINATION, game.dayNumber,
                ).map { it.voterUserId }.toSet()
                alivePlayers.filter { it.canVote && it.userId !in voted }.map { it.userId }
            }
            "VOTE_RESULT" -> listOf("host:${game.hostUserId}")
            "HUNTER_SHOOT" -> alivePlayers.filter { it.role == PlayerRole.HUNTER }.map { it.userId }
            "BADGE_HANDOVER" -> alivePlayers.filter { it.sheriff }.map { it.userId }
            else -> emptyList()
        }
    }

    private fun sheriffWaitingOn(gameId: Int, game: Game, alivePlayers: List<GamePlayer>): List<String> {
        val se = sheriffElectionRepository.findByGameId(gameId).orElse(null) ?: return emptyList()
        return when (se.subPhase) {
            // SIGNUP: any alive player can still campaign; host can advance to SPEECH
            ElectionSubPhase.SIGNUP -> listOf("host:${game.hostUserId}") + alivePlayers.map { it.userId }
            ElectionSubPhase.SPEECH -> {
                val order = se.speakingOrder?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                val current = order.getOrNull(se.currentSpeakerIdx)
                if (current != null) listOf(current) else listOf("host:${game.hostUserId}")
            }
            ElectionSubPhase.VOTING -> {
                val voted = voteRepository.findByGameIdAndVoteContextAndDayNumber(
                    gameId, VoteContext.SHERIFF_ELECTION, game.dayNumber,
                ).map { it.voterUserId }.toSet()
                alivePlayers.filter { it.userId !in voted }.map { it.userId }
            }
            ElectionSubPhase.RESULT, ElectionSubPhase.TIED -> listOf("host:${game.hostUserId}")
        }
    }
}
