package com.werewolf.repository

import com.werewolf.model.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.*

interface UserRepository : JpaRepository<User, String>

interface RoomRepository : JpaRepository<Room, Int> {
    fun findByRoomCode(roomCode: String): Optional<Room>

    /**
     * Resolve the room currently "using" [code]. Room codes are reusable: once a
     * room's game has ended, its code is free to recycle (see [findActiveByRoomCode]
     * usage in RoomService.generateCode). A code is considered in use only while a
     * room is still WAITING or has a game that has not ended — finished/closed rooms
     * holding a recycled code are ignored. At most one such room exists per code.
     */
    @Query(
        """
        SELECT r FROM Room r
        WHERE r.roomCode = :code
          AND (r.status = com.werewolf.model.RoomStatus.WAITING
               OR EXISTS (SELECT g FROM Game g WHERE g.roomId = r.roomId AND g.endedAt IS NULL))
        """,
    )
    fun findActiveByRoomCode(code: String): Optional<Room>

    /**
     * Rooms the user belongs to that are still active (WAITING or with a live
     * game) — used by the lobby's quick-rejoin lookup so a player who closed the
     * app can jump straight back in. Most-recent room first.
     */
    @Query(
        """
        SELECT r FROM Room r
        WHERE EXISTS (SELECT rp FROM RoomPlayer rp WHERE rp.roomId = r.roomId AND rp.userId = :userId)
          AND (r.status = com.werewolf.model.RoomStatus.WAITING
               OR EXISTS (SELECT g FROM Game g WHERE g.roomId = r.roomId AND g.endedAt IS NULL))
        ORDER BY r.roomId DESC
        """,
    )
    fun findActiveRoomsForUser(userId: String): List<Room>
}

interface RoomPlayerRepository : JpaRepository<RoomPlayer, Int> {
    fun findByRoomId(roomId: Int): List<RoomPlayer>
    fun findByRoomIdAndUserId(roomId: Int, userId: String): Optional<RoomPlayer>
    fun existsByRoomIdAndSeatIndex(roomId: Int, seatIndex: Int): Boolean

    @Modifying
    @Query("UPDATE RoomPlayer rp SET rp.status = :status WHERE rp.roomId = :roomId AND rp.userId = :userId")
    fun updateStatus(roomId: Int, userId: String, status: ReadyStatus): Int

    @Modifying
    @Query("UPDATE RoomPlayer rp SET rp.status = com.werewolf.model.ReadyStatus.READY WHERE rp.roomId = :roomId AND rp.userId = :userId AND rp.seatIndex IS NOT NULL")
    fun setReadyIfSeated(roomId: Int, userId: String): Int

    @Modifying
    @Query("UPDATE RoomPlayer rp SET rp.seatIndex = :seatIndex WHERE rp.roomId = :roomId AND rp.userId = :userId")
    fun updateSeatIndex(roomId: Int, userId: String, seatIndex: Int): Int
}

interface GameRepository : JpaRepository<Game, Int> {
    fun findByRoomIdAndEndedAtIsNull(roomId: Int): Optional<Game>
    fun findByEndedAtIsNull(): List<Game>
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

    fun findByGameIdAndVoteContextAndDayNumberAndVoterUserId(
        gameId: Int,
        voteContext: VoteContext,
        dayNumber: Int,
        voterUserId: String,
    ): Optional<Vote>
}

interface EliminationHistoryRepository : JpaRepository<EliminationHistory, Int> {
    fun findByGameId(gameId: Int): List<EliminationHistory>
    fun findByGameIdAndDayNumber(gameId: Int, dayNumber: Int): Optional<EliminationHistory>
}

interface GameEventRepository : JpaRepository<GameEvent, Int> {
    fun findByGameIdOrderByCreatedAtAsc(gameId: Int): List<GameEvent>
}
