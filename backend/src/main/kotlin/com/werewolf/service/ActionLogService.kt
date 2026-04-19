package com.werewolf.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.werewolf.dto.ActionLogEntryDto
import com.werewolf.model.*
import com.werewolf.repository.GameEventRepository
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.UserRepository
import org.springframework.stereotype.Service

@Service
class ActionLogService(
    private val gameEventRepository: GameEventRepository,
    private val userRepository: UserRepository,
    private val gamePlayerRepository: GamePlayerRepository,
) {
    private val mapper = jacksonObjectMapper()

    fun recordNightDeaths(gameId: Int, dayNumber: Int, killedIds: List<String>) {
        if (killedIds.isEmpty()) return
        val users = userRepository.findAllById(killedIds).associateBy { it.userId }
        val players = gamePlayerRepository.findByGameId(gameId).associateBy { it.userId }
        killedIds.forEach { userId ->
            val payload = mapOf(
                "dayNumber"  to dayNumber,
                "userId"     to userId,
                "nickname"   to (users[userId]?.nickname ?: userId),
                "seatIndex"  to (players[userId]?.seatIndex ?: 0),
            )
            gameEventRepository.save(
                GameEvent(
                    gameId       = gameId,
                    eventType    = "NIGHT_DEATH",
                    message      = mapper.writeValueAsString(payload),
                    targetUserId = userId,
                )
            )
        }
    }

    fun recordVoteResult(
        gameId: Int,
        dayNumber: Int,
        votes: List<Vote>,
        tally: Map<String, Double>,
        sheriffUserId: String?,
        eliminatedUserId: String?,
        eliminatedRole: PlayerRole?,
    ) {
        val allUserIds = (votes.map { it.voterUserId } +
            votes.mapNotNull { it.targetUserId } +
            listOfNotNull(eliminatedUserId)).distinct()
        val users = userRepository.findAllById(allUserIds).associateBy { it.userId }
        val players = gamePlayerRepository.findByGameId(gameId).associateBy { it.userId }

        val tallyList = tally.entries
            .sortedByDescending { it.value }
            .map { (targetId, count) ->
                val voters = votes
                    .filter { it.targetUserId == targetId }
                    .map { v ->
                        mapOf(
                            "userId"    to v.voterUserId,
                            "nickname"  to (users[v.voterUserId]?.nickname ?: v.voterUserId),
                            "seatIndex" to (players[v.voterUserId]?.seatIndex ?: 0),
                        )
                    }
                mapOf(
                    "userId"    to targetId,
                    "nickname"  to (users[targetId]?.nickname ?: targetId),
                    "seatIndex" to (players[targetId]?.seatIndex ?: 0),
                    "votes"     to count,
                    "voters"    to voters,
                )
            }

        val payload = mapOf(
            "dayNumber"           to dayNumber,
            "tally"               to tallyList,
            "eliminatedUserId"    to eliminatedUserId,
            "eliminatedNickname"  to eliminatedUserId?.let { users[it]?.nickname ?: it },
            "eliminatedSeatIndex" to eliminatedUserId?.let { players[it]?.seatIndex },
            "eliminatedRole"      to eliminatedRole?.name,
        )
        gameEventRepository.save(
            GameEvent(
                gameId       = gameId,
                eventType    = "VOTE_RESULT",
                message      = mapper.writeValueAsString(payload),
                targetUserId = eliminatedUserId,
            )
        )
    }

    fun recordHunterShot(gameId: Int, dayNumber: Int, hunterUserId: String, targetUserId: String) {
        val users = userRepository.findAllById(listOf(hunterUserId, targetUserId)).associateBy { it.userId }
        val players = gamePlayerRepository.findByGameId(gameId).associateBy { it.userId }
        val payload = mapOf(
            "dayNumber"       to dayNumber,
            "hunterUserId"    to hunterUserId,
            "hunterNickname"  to (users[hunterUserId]?.nickname ?: hunterUserId),
            "hunterSeatIndex" to (players[hunterUserId]?.seatIndex ?: 0),
            "targetUserId"    to targetUserId,
            "targetNickname"  to (users[targetUserId]?.nickname ?: targetUserId),
            "targetSeatIndex" to (players[targetUserId]?.seatIndex ?: 0),
        )
        gameEventRepository.save(
            GameEvent(
                gameId       = gameId,
                eventType    = "HUNTER_SHOT",
                message      = mapper.writeValueAsString(payload),
                targetUserId = targetUserId,
            )
        )
    }

    fun recordIdiotReveal(gameId: Int, dayNumber: Int, userId: String) {
        val user = userRepository.findById(userId).orElse(null)
        val player = gamePlayerRepository.findByGameIdAndUserId(gameId, userId).orElse(null)
        val payload = mapOf(
            "dayNumber" to dayNumber,
            "userId"    to userId,
            "nickname"  to (user?.nickname ?: userId),
            "seatIndex" to (player?.seatIndex ?: 0),
        )
        gameEventRepository.save(
            GameEvent(
                gameId       = gameId,
                eventType    = "IDIOT_REVEAL",
                message      = mapper.writeValueAsString(payload),
                targetUserId = userId,
            )
        )
    }

    fun getLog(gameId: Int): List<ActionLogEntryDto> =
        gameEventRepository.findByGameIdOrderByCreatedAtAsc(gameId).map { e ->
            ActionLogEntryDto(
                id           = e.id ?: 0,
                eventType    = e.eventType,
                message      = e.message,
                targetUserId = e.targetUserId,
                createdAt    = e.createdAt?.toString(),
            )
        }
}
