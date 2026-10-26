package com.werewolf.service

import com.werewolf.model.RoomStatus
import com.werewolf.repository.RoomRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Closes WAITING rooms nobody has used for [STALE_AFTER_HOURS]. Otherwise they
 * wait forever — only the last member tapping Leave closes a room, and a
 * backend restart turns a mid-game room back into WAITING — and each one keeps
 * its 3-digit code and shows up in members' lobby Rejoin. Prod had 63 such
 * rooms. Perks held in them are refunded by [PerkRefundSweep] on the same
 * 24h clock. Runs at startup and then hourly (fixedDelay).
 */
@Component
class StaleRoomSweep(private val roomRepository: RoomRepository) {
    private val log = LoggerFactory.getLogger(StaleRoomSweep::class.java)

    @Scheduled(fixedDelayString = "\${werewolf.rooms.sweep-interval-ms:3600000}")
    @Transactional
    fun closeStaleRooms() {
        val now = LocalDateTime.now()
        val stale = roomRepository.findWaitingIdleSince(now.minusHours(STALE_AFTER_HOURS))
        if (stale.isEmpty()) return
        stale.forEach {
            it.status = RoomStatus.CLOSED
            it.closedAt = now
        }
        roomRepository.saveAll(stale)
        log.info("[room-sweep] closed {} stale waiting rooms: {}", stale.size, stale.map { it.roomCode })
    }

    companion object {
        const val STALE_AFTER_HOURS = 24L
    }
}
