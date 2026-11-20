package com.werewolf.controller

import com.stripe.exception.SignatureVerificationException
import com.werewolf.config.PaymentProperties
import com.werewolf.service.GuestPurchaseException
import com.werewolf.service.OrderNotFoundException
import com.werewolf.service.PaymentService
import com.werewolf.service.PaymentsUnavailableException
import com.werewolf.service.ProductNotFoundException
import com.werewolf.service.StripeWebhookVerifier
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

data class CheckoutRequest(val productKey: String)

@RestController
@RequestMapping("/api/payment")
class PaymentController(
    private val paymentService: PaymentService,
    private val webhookVerifier: StripeWebhookVerifier,
    private val props: PaymentProperties,
) {
    private val log = LoggerFactory.getLogger(PaymentController::class.java)

    @GetMapping("/products")
    fun products(): ResponseEntity<Any> = ResponseEntity.ok(paymentService.products())

    @PostMapping("/checkout")
    fun checkout(
        @RequestBody body: CheckoutRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val userId = authentication.principal as String
        return try {
            val url = paymentService.createCheckout(userId, body.productKey)
            ResponseEntity.ok(mapOf("success" to true, "checkoutUrl" to url))
        } catch (e: GuestPurchaseException) {
            ResponseEntity.status(403).body(mapOf("error" to e.message))
        } catch (e: PaymentsUnavailableException) {
            ResponseEntity.status(503).body(mapOf("error" to e.message))
        } catch (e: ProductNotFoundException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }
    }

    /**
     * Stripe webhook (permitAll route — authenticity comes from the
     * Stripe-Signature verification, not a JWT). 200 once a verified event is
     * handled (or deliberately ignored); 400 for a bad signature; 503 while
     * payments are switched off and 5xx when processing fails, so Stripe retries.
     */
    @PostMapping("/webhook")
    fun webhook(
        @RequestBody payload: String,
        @RequestHeader("Stripe-Signature", required = false) signature: String?,
    ): ResponseEntity<Any> {
        if (!props.enabled) return ResponseEntity.status(503).body(mapOf("error" to "payments disabled"))
        val event = try {
            webhookVerifier.verify(payload, signature)
        } catch (e: SignatureVerificationException) {
            log.warn("[payment] webhook signature verification failed: {}", e.message)
            return ResponseEntity.badRequest().body(mapOf("error" to "invalid signature"))
        }
        paymentService.handleEvent(event)
        return ResponseEntity.ok(mapOf("received" to true))
    }

    /** One page of the caller's own Stripe orders, newest first: { orders, hasMore }. */
    @GetMapping("/orders")
    fun orders(
        @RequestParam(defaultValue = "0") page: Int,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val userId = authentication.principal as String
        return ResponseEntity.ok(paymentService.listOrders(userId, page))
    }

    /** Owner-only fulfillment poll for the success page (redirects are not trusted). */
    @GetMapping("/order/{orderNo}")
    fun order(
        @PathVariable orderNo: String,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val userId = authentication.principal as String
        return try {
            ResponseEntity.ok(paymentService.orderStatus(userId, orderNo))
        } catch (e: OrderNotFoundException) {
            ResponseEntity.status(404).body(mapOf("error" to e.message))
        }
    }
}
