package com.werewolf.service

import com.werewolf.config.AdminProperties
import com.werewolf.controller.guestUserId
import com.werewolf.model.CreditTxType
import com.werewolf.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

/** The one admin: the guest player "Ray" (guest ids derive from the nickname). */
val ADMIN_USER_ID: String = guestUserId("Ray")

/** Largest single grant; there is no other limit (no daily cap, no balance cap). */
const val MAX_ADMIN_GRANT = 100

data class AdminPlayer(val userId: String, val nickname: String, val balance: Int)

class NotAdminException : RuntimeException("Admin only")
class AdminPinNotConfiguredException : RuntimeException("Admin PIN is not configured")
class WrongAdminPinException : RuntimeException("Wrong PIN")
class InvalidGrantAmountException : RuntimeException("Amount must be between 1 and $MAX_ADMIN_GRANT")
class PlayerNotFoundException : RuntimeException("Player not found")

/**
 * Admin credit grants: only [ADMIN_USER_ID], confirmed with the 4-digit PIN
 * from ADMIN_PIN, to players that already exist in the users table.
 */
@Service
class AdminService(
    private val adminProperties: AdminProperties,
    private val userRepository: UserRepository,
    private val walletService: WalletService,
) {

    fun isAdmin(userId: String): Boolean = userId == ADMIN_USER_ID

    @Transactional(readOnly = true)
    fun searchPlayers(adminId: String, query: String): List<AdminPlayer> {
        requireAdmin(adminId)
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return userRepository.findTop20ByNicknameContainingIgnoreCaseOrderByNicknameAsc(q)
            .map { AdminPlayer(it.userId, it.nickname, walletService.balance(it.userId)) }
    }

    /** Credits [amount] to [targetUserId] and returns the player's new balance. */
    @Transactional
    fun grant(adminId: String, targetUserId: String, amount: Int, pin: String): AdminPlayer {
        requireAdmin(adminId)
        val expected = adminProperties.pin
        if (expected.isBlank()) throw AdminPinNotConfiguredException()
        // Constant-time compare so response timing does not leak PIN digits.
        if (!MessageDigest.isEqual(pin.toByteArray(), expected.toByteArray())) throw WrongAdminPinException()
        if (amount !in 1..MAX_ADMIN_GRANT) throw InvalidGrantAmountException()
        val player = userRepository.findById(targetUserId).orElseThrow { PlayerNotFoundException() }
        val balance = walletService.credit(targetUserId, amount, CreditTxType.ADMIN_GRANT, note = "Admin grant")
        return AdminPlayer(player.userId, player.nickname, balance)
    }

    private fun requireAdmin(userId: String) {
        if (!isAdmin(userId)) throw NotAdminException()
    }
}
