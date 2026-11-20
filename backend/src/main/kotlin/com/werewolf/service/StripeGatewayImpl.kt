package com.werewolf.service

import com.stripe.exception.InvalidRequestException
import com.stripe.model.Charge
import com.stripe.model.Customer
import com.stripe.model.Dispute
import com.stripe.model.Refund
import com.stripe.model.checkout.Session
import com.stripe.net.RequestOptions
import com.stripe.param.ChargeRetrieveParams
import com.stripe.param.CustomerCreateParams
import com.stripe.param.DisputeListParams
import com.stripe.param.RefundListParams
import com.stripe.param.checkout.SessionCreateParams
import com.stripe.param.checkout.SessionListParams
import com.stripe.param.checkout.SessionRetrieveParams
import com.werewolf.service.StripeResourceMissingException.Resource
import org.springframework.stereotype.Component

/**
 * [StripeGateway] over the stripe-java static API (the key is set by PaymentService).
 * Only maps; no business logic. Exercised against the sandbox by StripeSandboxIntegrationTest.
 */
@Component
class StripeGatewayImpl : StripeGateway {

    override fun createCheckoutSession(req: CheckoutRequest, idempotencyKey: String): CheckoutSnapshot {
        val params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setCustomer(req.customerId)
            .setClientReferenceId(req.orderNo)
            .setExpiresAt(req.expiresAtEpochSec)
            .putMetadata("orderNo", req.orderNo)
            .putMetadata("userId", req.userId)
            .setPaymentIntentData(
                SessionCreateParams.PaymentIntentData.builder()
                    .putMetadata("orderNo", req.orderNo)
                    .putMetadata("userId", req.userId)
                    .build(),
            )
            .setSuccessUrl(req.successUrl)
            .setCancelUrl(req.cancelUrl)
            .addLineItem(
                SessionCreateParams.LineItem.builder()
                    .setQuantity(1)
                    .setPriceData(
                        SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency(req.currency)
                            .setUnitAmount(req.unitAmountCents)
                            .setProductData(
                                SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(req.productName)
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()
        val options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build()
        return try {
            Session.create(params, options).toSnapshot()
        } catch (e: InvalidRequestException) {
            if (e.code == "resource_missing" && e.param == "customer") throw StripeResourceMissingException(Resource.CUSTOMER, req.customerId, e)
            throw e
        }
    }

    override fun retrieveSession(sessionId: String): CheckoutSnapshot = retrieve(sessionId).toSnapshot()

    override fun expireSession(sessionId: String): CheckoutSnapshot {
        val session = retrieve(sessionId)
        if (session.status != "open") return session.toSnapshot()
        return try {
            session.expire().toSnapshot()
        } catch (e: InvalidRequestException) {
            retrieveSession(sessionId) // completed (paid) between our read and the expire call
        }
    }

    override fun listCompletedSessions(createdAfterEpochSec: Long): Sequence<CheckoutSnapshot> {
        val params = SessionListParams.builder()
            .setStatus(SessionListParams.Status.COMPLETE)
            .setCreated(SessionListParams.Created.builder().setGte(createdAfterEpochSec).build())
            .setLimit(100L)
            .build()
        // Not expanded: chargeId / receiptUrl are null here; callers re-read via fulfill().
        return Session.list(params).autoPagingIterable().asSequence().map { it.toSnapshot() }
    }

    override fun createCustomer(userId: String): String =
        Customer.create(CustomerCreateParams.builder().putMetadata("userId", userId).build()).id

    // stripe-java 33 (dahlia): Charge has no dispute field and its refunds list is not reliably expandable,
    // so refunds and the dispute are read with list calls filtered by charge.
    override fun retrieveCharge(chargeId: String): ChargeSnapshot {
        val charge = try {
            Charge.retrieve(chargeId, ChargeRetrieveParams.builder().addExpand("payment_intent").build(), null)
        } catch (e: InvalidRequestException) {
            if (e.code == "resource_missing") throw StripeResourceMissingException(Resource.CHARGE, chargeId, e)
            throw e
        }
        val refunds = Refund.list(RefundListParams.builder().setCharge(chargeId).setLimit(100L).build())
            .autoPagingIterable().map { RefundInfo(it.id, it.amount, it.status) }
        val dispute = Dispute.list(DisputeListParams.builder().setCharge(chargeId).setLimit(1L).build()).data.firstOrNull()
        return ChargeSnapshot(
            chargeId = charge.id,
            paymentIntentId = charge.paymentIntent,
            orderNo = charge.paymentIntentObject?.metadata?.get("orderNo"),
            amount = charge.amount,
            refunds = refunds,
            disputeStatus = dispute?.status,
        )
    }

    override fun listRecentChargeIdsWithRefundsOrDisputes(createdAfterEpochSec: Long): Sequence<String> {
        val refunds = Refund.list(
            RefundListParams.builder().setCreated(RefundListParams.Created.builder().setGte(createdAfterEpochSec).build())
                .setLimit(100L).build(),
        ).autoPagingIterable().asSequence().mapNotNull { it.charge }
        val disputes = Dispute.list(
            DisputeListParams.builder().setCreated(DisputeListParams.Created.builder().setGte(createdAfterEpochSec).build())
                .setLimit(100L).build(),
        ).autoPagingIterable().asSequence().mapNotNull { it.charge }
        return (refunds + disputes).distinct()
    }

    private fun retrieve(sessionId: String): Session = try {
        Session.retrieve(
            sessionId,
            SessionRetrieveParams.builder().addExpand("payment_intent.latest_charge").build(),
            null,
        )
    } catch (e: InvalidRequestException) {
        if (e.code == "resource_missing") throw StripeResourceMissingException(Resource.SESSION, sessionId, e)
        throw e
    }

    private fun Session.toSnapshot() = CheckoutSnapshot(
        sessionId = id,
        status = status,
        paymentStatus = paymentStatus,
        orderNo = metadata?.get("orderNo"),
        clientReferenceId = clientReferenceId,
        paymentIntentId = paymentIntent,
        chargeId = paymentIntentObject?.latestCharge,
        receiptUrl = paymentIntentObject?.latestChargeObject?.receiptUrl,
        url = url,
        expiresAtEpochSec = expiresAt,
    )
}
