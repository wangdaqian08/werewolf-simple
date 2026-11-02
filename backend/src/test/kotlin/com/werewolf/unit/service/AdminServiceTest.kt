package com.werewolf.unit.service

import com.werewolf.config.AdminProperties
import com.werewolf.model.User
import com.werewolf.repository.UserRepository
import com.werewolf.service.ADMIN_USER_ID
import com.werewolf.service.AdminPinLockedException
import com.werewolf.service.AdminPinNotConfiguredException
import com.werewolf.service.AdminService
import com.werewolf.service.WalletService
import com.werewolf.service.WrongAdminPinException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant
import java.util.Optional

class AdminServiceTest {

    @Test
    fun `with no ADMIN_PIN set, every grant is refused, even an empty PIN`() {
        val wallet = mock<WalletService>()
        val service = AdminService(AdminProperties(pin = ""), mock<UserRepository>(), wallet)

        assertThrows(AdminPinNotConfiguredException::class.java) {
            service.grant(ADMIN_USER_ID, "guest:alice", 10, "")
        }
        verify(wallet, never()).credit(any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `five wrong PINs lock grants for 15 minutes, even with the right PIN`() {
        val users = mock<UserRepository>()
        whenever(users.findById("guest:alice")).thenReturn(Optional.of(User(userId = "guest:alice", nickname = "alice")))
        val wallet = mock<WalletService>()
        val service = AdminService(AdminProperties(pin = "1234"), users, wallet)
        var now = Instant.parse("2026-11-02T10:00:00Z")
        service.clock = { now }

        repeat(5) {
            assertThrows(WrongAdminPinException::class.java) { service.grant(ADMIN_USER_ID, "guest:alice", 10, "0000") }
        }
        assertThrows(AdminPinLockedException::class.java) { service.grant(ADMIN_USER_ID, "guest:alice", 10, "1234") }
        verify(wallet, never()).credit(any(), any(), any(), any(), any(), any(), any())

        now = now.plus(Duration.ofMinutes(15))
        service.grant(ADMIN_USER_ID, "guest:alice", 10, "1234")
        verify(wallet).credit(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }
}
