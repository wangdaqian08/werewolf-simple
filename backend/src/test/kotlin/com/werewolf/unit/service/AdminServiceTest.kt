package com.werewolf.unit.service

import com.werewolf.config.AdminProperties
import com.werewolf.repository.UserRepository
import com.werewolf.service.ADMIN_USER_ID
import com.werewolf.service.AdminPinNotConfiguredException
import com.werewolf.service.AdminService
import com.werewolf.service.WalletService
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

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
}
