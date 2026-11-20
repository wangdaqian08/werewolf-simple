package com.werewolf.controller

import com.werewolf.repository.CreditTransactionRepository
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.service.WalletService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/wallet")
class WalletController(
    private val walletService: WalletService,
    private val creditTransactionRepository: CreditTransactionRepository,
    private val paymentOrderRepository: PaymentOrderRepository,
) {

    @GetMapping
    fun getWallet(authentication: Authentication): ResponseEntity<Any> {
        val userId = authentication.principal as String
        val rows = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId)
        val orderNos = paymentOrderRepository.findAllById(rows.mapNotNull { it.paymentOrderId }.toSet())
            .associate { it.id to it.orderNo }
        val recent = rows.map {
            mapOf(
                "type" to it.type.name,
                "amount" to it.amount,
                "balanceAfter" to it.balanceAfter,
                "note" to it.note,
                "orderNo" to orderNos[it.paymentOrderId],
                "createdAt" to it.createdAt.toString(),
            )
        }
        return ResponseEntity.ok(
            mapOf(
                "balance" to walletService.balance(userId),
                "recent" to recent,
            ),
        )
    }
}
