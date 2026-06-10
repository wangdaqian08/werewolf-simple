package com.werewolf.service

import com.werewolf.model.CreditTransaction
import com.werewolf.model.CreditTxType
import com.werewolf.model.Wallet
import com.werewolf.repository.CreditTransactionRepository
import com.werewolf.repository.WalletRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Single entry point for all credit balance changes. Every debit/credit is an
 * atomic conditional UPDATE (no read-modify-write) paired with a ledger row in
 * credit_transactions carrying the post-change balance.
 */
@Service
class WalletService(
    private val walletRepository: WalletRepository,
    private val creditTransactionRepository: CreditTransactionRepository,
) {

    @Transactional(propagation = Propagation.REQUIRED)
    fun getOrCreate(userId: String): Wallet =
        walletRepository.findById(userId).orElseGet {
            try {
                walletRepository.save(Wallet(userId = userId))
            } catch (e: DataIntegrityViolationException) {
                // concurrent creation — the row exists now
                walletRepository.findById(userId).orElseThrow { e }
            }
        }

    /**
     * Debit [amount] credits (amount > 0). Returns the new balance, or null if
     * the wallet has insufficient funds — nothing is written in that case.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    fun debit(
        userId: String,
        amount: Int,
        type: CreditTxType,
        gameId: Int? = null,
        perkActivationId: Int? = null,
        paymentOrderId: Int? = null,
        note: String? = null,
    ): Int? {
        require(amount > 0) { "debit amount must be > 0, got $amount" }
        getOrCreate(userId)
        if (walletRepository.tryDebit(userId, amount) == 0) return null
        return record(userId, -amount, type, gameId, perkActivationId, paymentOrderId, note)
    }

    /** Credit [amount] credits (amount > 0). Returns the new balance. */
    @Transactional(propagation = Propagation.REQUIRED)
    fun credit(
        userId: String,
        amount: Int,
        type: CreditTxType,
        gameId: Int? = null,
        perkActivationId: Int? = null,
        paymentOrderId: Int? = null,
        note: String? = null,
    ): Int {
        require(amount > 0) { "credit amount must be > 0, got $amount" }
        getOrCreate(userId)
        walletRepository.credit(userId, amount)
        return record(userId, amount, type, gameId, perkActivationId, paymentOrderId, note)
    }

    @Transactional(readOnly = true)
    fun balance(userId: String): Int = walletRepository.getBalance(userId) ?: 0

    private fun record(
        userId: String,
        signedAmount: Int,
        type: CreditTxType,
        gameId: Int?,
        perkActivationId: Int?,
        paymentOrderId: Int?,
        note: String?,
    ): Int {
        val balanceAfter = walletRepository.getBalance(userId)
            ?: error("wallet missing for $userId after balance update")
        creditTransactionRepository.save(
            CreditTransaction(
                userId = userId,
                type = type,
                amount = signedAmount,
                balanceAfter = balanceAfter,
                gameId = gameId,
                perkActivationId = perkActivationId,
                paymentOrderId = paymentOrderId,
                note = note,
            ),
        )
        return balanceAfter
    }
}
