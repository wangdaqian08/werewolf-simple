package com.werewolf.service

import com.werewolf.auth.AuthService
import com.werewolf.config.GameTimingProperties
import com.werewolf.controller.BgmTrackRegistry
import com.werewolf.dto.PerkActivationDto
import com.werewolf.dto.RoomConfigDto
import com.werewolf.dto.RoomConfigRequest
import com.werewolf.dto.RoomDto
import com.werewolf.dto.RoomPlayerDto
import com.werewolf.model.*
import com.werewolf.repository.GameRepository
import com.werewolf.repository.PerkActivationRepository
import com.werewolf.repository.PerkRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RoomService(
    private val roomRepository: RoomRepository,
    private val roomPlayerRepository: RoomPlayerRepository,
    private val userRepository: UserRepository,
    private val gameRepository: GameRepository,
    private val authService: AuthService,
    private val stompPublisher: StompPublisher,
    private val timing: GameTimingProperties,
    private val bgmRegistry: BgmTrackRegistry,
    private val perkActivationRepository: PerkActivationRepository,
    private val perkRepository: PerkRepository,
    private val perkService: PerkService,
) {
    @Transactional
    fun createRoom(
        userId: String,
        nickname: String,
        avatarUrl: String?,
        cfg: RoomConfigRequest,
        displayNameOverride: String? = null,
    ): RoomDto {
        authService.loginOrRegister(userId, nickname, avatarUrl)

        if (!bgmRegistry.isValidTrack(cfg.bgmTrack)) {
            throw InvalidBgmTrackException("Unknown BGM track: ${cfg.bgmTrack}")
        }

        validateRoleComposition(cfg.totalPlayers, cfg.wolfCount, cfg.roles)

        val room = roomRepository.save(
            Room(
                roomCode = generateCode(),
                hostUserId = userId,
                totalPlayers = cfg.totalPlayers,
                wolfCount = cfg.wolfCount,
                hasSeer = PlayerRole.SEER in cfg.roles,
                hasWitch = PlayerRole.WITCH in cfg.roles,
                hasHunter = PlayerRole.HUNTER in cfg.roles,
                hasGuard = PlayerRole.GUARD in cfg.roles,
                hasIdiot = PlayerRole.IDIOT in cfg.roles,
                hasSheriff = cfg.hasSheriff,
                winCondition = cfg.winCondition,
                config = buildGameConfig(cfg.bgmTrack, cfg.witchSelfSaveAllowed, cfg.perksAllowed),
            )
        )
        val roomId = room.roomId ?: error("Failed to persist room")
        roomPlayerRepository.save(
            RoomPlayer(
                roomId = roomId,
                userId = userId,
                host = true,
                displayName = sanitizeDisplayName(displayNameOverride),
            )
        )

        return buildRoomDto(room)
    }

    @Transactional
    fun joinRoom(
        userId: String,
        nickname: String,
        avatarUrl: String?,
        roomCode: String,
        displayNameOverride: String? = null,
    ): RoomDto {
        authService.loginOrRegister(userId, nickname, avatarUrl)

        val room = roomRepository.findActiveByRoomCode(roomCode).orElse(null)
            ?: throw RoomNotFoundException("Room not found")
        val roomId = room.roomId ?: error("Room has no ID")

        // Existing member can rejoin at any game stage. Supports:
        //   1. Network drop / mobile backgrounding mid-game — player navigates
        //      back to /room/<code> from the lobby and gets a fresh room snapshot.
        //   2. Page refresh during ROLE_REVEAL / NIGHT / DAY / SHERIFF_ELECTION.
        //   3. Kick + re-add: a kicked player's row is deleted (kickPlayer below),
        //      so they fall through to the "new player" branch and are bound
        //      by the WAITING + full guards just like a brand-new joiner.
        // Re-join is idempotent — we deliberately do NOT update displayName here
        // so a refresh / mobile reconnect can't accidentally rename a player
        // mid-game from a stale form value.
        if (roomPlayerRepository.findByRoomIdAndUserId(roomId, userId).isPresent) {
            return buildRoomDto(room)
        }

        // New player: must be in WAITING and room not full.
        if (room.status != RoomStatus.WAITING)
            throw RoomNotOpenException("Room is not open")
        val count = roomPlayerRepository.findByRoomId(roomId).size
        if (count >= room.totalPlayers) throw RoomFullException("Room is full")
        roomPlayerRepository.save(
            RoomPlayer(
                roomId = roomId,
                userId = userId,
                displayName = sanitizeDisplayName(displayNameOverride),
            )
        )

        return buildRoomDto(room)
    }

    /**
     * Trim, then null out blanks. Bean Validation already caps the length at
     * 50 chars at the controller boundary; this is the storage-side normaliser.
     */
    private fun sanitizeDisplayName(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Host removes a player from the room. Only valid during the WAITING stage
     * (room hasn't started a game yet). Deletes the player's row so they fall
     * through to the "new player" branch on a re-join attempt — meaning they
     * can re-join only if (a) the room is still WAITING and (b) the seat
     * hasn't been filled by someone else, and they CANNOT re-join once the
     * host clicks Start Game.
     *
     * Broadcasts:
     *   - PLAYER_KICKED — kicked player's frontend redirects to lobby.
     *   - ROOM_UPDATE   — other players see the seat empty.
     */
    @Transactional
    fun kickPlayer(hostUserId: String, roomId: Int, targetUserId: String) {
        val room = roomRepository.findById(roomId).orElse(null)
            ?: throw RoomNotFoundException("Room not found")
        if (room.status != RoomStatus.WAITING)
            throw RoomNotOpenException("Cannot kick after the game has started")
        if (room.hostUserId != hostUserId)
            throw NotHostException("Only the host can kick players")
        if (targetUserId == hostUserId)
            throw CannotKickHostException("Host cannot kick themselves")

        val target = roomPlayerRepository.findByRoomIdAndUserId(roomId, targetUserId).orElse(null)
            ?: throw PlayerNotInRoomException("Player not in room")

        roomPlayerRepository.delete(target)

        // Refund any perk the kicked player had activated — they paid for a
        // game they can no longer play in.
        perkService.refundActiveForUser(roomId, targetUserId)

        stompPublisher.broadcastRoomAfterCommit(roomId, mapOf("type" to "PLAYER_KICKED",
            "payload" to mapOf("userId" to targetUserId)))
        stompPublisher.broadcastRoomAfterCommit(roomId, mapOf("type" to "ROOM_UPDATE",
            "payload" to mapOf("players" to buildRoomDto(room).players)))
    }

    @Transactional
    fun setReady(userId: String, roomId: Int, ready: Boolean) {
        val room = roomRepository.findById(roomId).orElse(null) ?: throw RoomNotFoundException("Room not found")
        if (room.status != RoomStatus.WAITING) throw RoomNotOpenException("Room is not open")

        val affected = if (ready) {
            roomPlayerRepository.setReadyIfSeated(roomId, userId)
        } else {
            roomPlayerRepository.updateStatus(roomId, userId, ReadyStatus.NOT_READY)
        }
        if (affected == 0) throw PlayerNotInRoomException("Player not in room or seat not yet claimed")

        stompPublisher.broadcastRoomAfterCommit(roomId, mapOf("type" to "ROOM_UPDATE",
            "payload" to mapOf("players" to buildRoomDto(room).players)))
    }

    @Transactional
    fun claimSeat(userId: String, roomId: Int, seatIndex: Int) {
        val room = roomRepository.findById(roomId).orElse(null) ?: throw RoomNotFoundException("Room not found")
        if (room.status != RoomStatus.WAITING) throw RoomNotOpenException("Room is not open")

        if (roomPlayerRepository.existsByRoomIdAndSeatIndex(roomId, seatIndex))
            throw SeatTakenException("Seat $seatIndex is already taken")

        val affected = roomPlayerRepository.updateSeatIndex(roomId, userId, seatIndex)
        if (affected == 0) throw PlayerNotInRoomException("Player not in room")

        stompPublisher.broadcastRoomAfterCommit(roomId, mapOf("type" to "ROOM_UPDATE",
            "payload" to mapOf("players" to buildRoomDto(room).players)))
    }

    @Transactional(readOnly = true)
    fun getRoom(roomId: Int): RoomDto {
        val room = roomRepository.findById(roomId).orElse(null)
            ?: throw RoomNotFoundException("Room not found")
        return buildRoomDto(room)
    }

    /**
     * The active room the caller currently belongs to (for the lobby's quick
     * rejoin), or null if none. "Active" = WAITING or with a live game; finished
     * rooms are ignored. The returned DTO carries `activeGameId` so the client
     * can jump straight into an in-progress game.
     */
    @Transactional(readOnly = true)
    fun findActiveRoomForUser(userId: String): RoomDto? {
        val room = roomRepository.findActiveRoomsForUser(userId).firstOrNull() ?: return null
        return buildRoomDto(room)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildRoomDto(room: Room): RoomDto {
        val players = roomPlayerRepository.findByRoomId(room.roomId ?: error("Room has no ID"))
        val userMap = userRepository.findAllById(players.map { it.userId }).associateBy { it.userId }

        val playerDtos = players.map { rp ->
            val user = userMap[rp.userId]
            RoomPlayerDto(
                userId = rp.userId,
                // Per-room override wins over the User row's nickname (which is
                // the OAuth-provided / guest-typed identity name).
                nickname = rp.displayName ?: user?.nickname ?: rp.userId,
                avatar = user?.avatarUrl,
                seatIndex = rp.seatIndex,
                status = rp.status.name,
                isHost = rp.host,
            )
        }

        val roles = buildList {
            add(PlayerRole.WEREWOLF)
            add(PlayerRole.VILLAGER)
            if (room.hasSeer) add(PlayerRole.SEER)
            if (room.hasWitch) add(PlayerRole.WITCH)
            if (room.hasHunter) add(PlayerRole.HUNTER)
            if (room.hasGuard) add(PlayerRole.GUARD)
            if (room.hasIdiot) add(PlayerRole.IDIOT)
        }

        val activeGameId = if (room.status == RoomStatus.IN_GAME) {
            gameRepository.findByRoomIdAndEndedAtIsNull(room.roomId!!).map { it.gameId }.orElse(null)
        } else null

        // Live perk activations are public to the whole room (fairness rule).
        val perkNames = perkRepository.findAll().associate { it.perkCode to it.name }
        val perkActivations = perkActivationRepository
            .findByRoomIdAndStatus(room.roomId!!, PerkActivationStatus.ACTIVE)
            .map { PerkActivationDto(it.userId, it.perkCode, perkNames[it.perkCode] ?: it.perkCode) }

        return RoomDto(
            roomId = room.roomId.toString(),
            roomCode = room.roomCode,
            hostId = room.hostUserId,
            status = room.status.name,
            players = playerDtos,
            config = RoomConfigDto(totalPlayers = room.totalPlayers, wolfCount = room.wolfCount, roles = roles, hasSheriff = room.hasSheriff, winCondition = room.winCondition, bgmTrack = room.config?.bgmTrack, witchSelfSaveAllowed = room.config?.witchSelfSaveAllowed ?: true, perksAllowed = room.config?.perksAllowed ?: true),
            activeGameId = activeGameId,
            perkActivations = perkActivations,
        )
    }

    /**
     * Generate a 3-digit numeric room code (000–999). The space is small (1000),
     * so codes are *reusable*: only rooms that are still active (WAITING or with a
     * non-ended game) reserve a code — see [RoomRepository.findActiveByRoomCode].
     * Retry until a code free among active rooms is found.
     */
    private fun generateCode(): String {
        repeat(ROOM_CODE_MAX_ATTEMPTS) {
            val code = (1..3).map { ('0'..'9').random() }.joinToString("")
            if (roomRepository.findActiveByRoomCode(code).isEmpty) return code
        }
        throw RoomCodeUnavailableException("No free room code available — too many active rooms")
    }

    /**
     * Backend enforces game-correctness invariants only:
     *   1. wolfCount must be > 0 (a wolfless game is not werewolf)
     *   2. wolves + gods ≤ totalPlayers (no seat overflow)
     *
     * The canonical ±1 bounds (`WolfCountBounds`) are a *frontend UX policy*
     * for the host-facing stepper — they intentionally do NOT gate the API,
     * so headless tests and future tooling can construct edge configurations
     * (e.g. small 4-player rooms with 2 wolves) without bypassing security.
     */
    private fun validateRoleComposition(
        totalPlayers: Int,
        wolfCount: Int,
        roles: List<PlayerRole>,
    ) {
        if (wolfCount <= 0) {
            throw InvalidRoleCompositionException(
                "wolfCount must be > 0, got $wolfCount",
            )
        }
        val godCount = roles.count {
            it != PlayerRole.WEREWOLF && it != PlayerRole.VILLAGER
        }
        if (wolfCount + godCount > totalPlayers) {
            throw InvalidRoleCompositionException(
                "Composition overflow: $wolfCount wolves + $godCount gods > $totalPlayers seats",
            )
        }
    }

    /**
     * Build the per-room GameConfig, folding in any werewolf.timing.* property
     * overrides. Production leaves the properties unset and gets the compile-time
     * role defaults; the test profile sets small values so CI completes quickly.
     */
    private fun buildGameConfig(bgmTrack: String?, witchSelfSaveAllowed: Boolean, perksAllowed: Boolean): GameConfig = GameConfig(
        roleDelays = mapOf(
            PlayerRole.WEREWOLF to timing.applyTo(PlayerRole.WEREWOLF),
            PlayerRole.SEER to timing.applyTo(PlayerRole.SEER),
            PlayerRole.WITCH to timing.applyTo(PlayerRole.WITCH),
            PlayerRole.GUARD to timing.applyTo(PlayerRole.GUARD),
        ),
        bgmTrack = bgmTrack,
        witchSelfSaveAllowed = witchSelfSaveAllowed,
        perksAllowed = perksAllowed,
    )
}

private const val ROOM_CODE_MAX_ATTEMPTS = 200

class RoomNotFoundException(message: String) : RuntimeException(message)
class RoomNotOpenException(message: String) : RuntimeException(message)
class RoomFullException(message: String) : RuntimeException(message)
class RoomCodeUnavailableException(message: String) : RuntimeException(message)
class PlayerNotInRoomException(message: String) : RuntimeException(message)
class SeatTakenException(message: String) : RuntimeException(message)
class NotHostException(message: String) : RuntimeException(message)
class CannotKickHostException(message: String) : RuntimeException(message)
class InvalidBgmTrackException(message: String) : RuntimeException(message)
class InvalidRoleCompositionException(message: String) : RuntimeException(message)
