package com.werewolf.repository

import com.werewolf.model.*
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface UserRepository : JpaRepository<User, String>

interface RoomRepository : JpaRepository<Room, Int> {
    fun findByRoomCode(roomCode: String): Optional<Room>
}

interface RoomPlayerRepository : JpaRepository<RoomPlayer, Int> {
    fun findByRoomId(roomId: Int): List<RoomPlayer>
    fun findByRoomIdAndUserId(roomId: Int, userId: String): Optional<RoomPlayer>
}

interface GameRepository : JpaRepository<Game, Int> {
    fun findByRoomIdAndEndedAtIsNull(roomId: Int): Optional<Game>
}

interface GamePlayerRepository : JpaRepository<GamePlayer, Int> {
    fun findByGameId(gameId: Int): List<GamePlayer>
    fun findByGameIdAndUserId(gameId: Int, userId: String): Optional<GamePlayer>
}

interface NightPhaseRepository : JpaRepository<NightPhase, Int> {
    fun findByGameIdAndDayNumber(gameId: Int, dayNumber: Int): Optional<NightPhase>
    fun findByGameId(gameId: Int): List<NightPhase>
}

interface SheriffElectionRepository : JpaRepository<SheriffElection, Int> {
    fun findByGameId(gameId: Int): Optional<SheriffElection>
}

interface SheriffCandidateRepository : JpaRepository<SheriffCandidate, Int> {
    fun findByElectionId(electionId: Int): List<SheriffCandidate>
}

interface VoteRepository : JpaRepository<Vote, Int> {
    fun findByGameIdAndVoteContextAndDayNumber(
        gameId: Int,
        voteContext: VoteContext,
        dayNumber: Int,
    ): List<Vote>
}

interface EliminationHistoryRepository : JpaRepository<EliminationHistory, Int> {
    fun findByGameId(gameId: Int): List<EliminationHistory>
    fun findByGameIdAndDayNumber(gameId: Int, dayNumber: Int): java.util.Optional<EliminationHistory>
}

interface GameEventRepository : JpaRepository<GameEvent, Int> {
    fun findByGameIdOrderByCreatedAtAsc(gameId: Int): List<GameEvent>
}
