package com.werewolf.support

import com.werewolf.service.StripeResourceMissingException
import com.werewolf.service.StripeResourceMissingException.Resource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test

class FakeStripeGatewayChargesTest {
    private val fake = FakeStripeGateway()

    @Test
    fun `a charge reports its refunds and dispute`() {
        fake.addCharge("ch_1", "pi_1", "ORD1", 500)
        assertThat(fake.retrieveCharge("ch_1").refunds).isEmpty()
        assertThat(fake.retrieveCharge("ch_1").disputeStatus).isNull()

        val refundId = fake.refund("ch_1", 200)
        fake.refund("ch_1", 100, "failed")
        fake.setRefundStatus("ch_1", refundId, "canceled")
        fake.openDispute("ch_1")
        fake.setDisputeStatus("ch_1", "lost")

        val snap = fake.retrieveCharge("ch_1")
        assertThat(snap.orderNo).isEqualTo("ORD1")
        assertThat(snap.amount).isEqualTo(500)
        assertThat(snap.refunds.map { it.amount to it.status }).containsExactly(200L to "canceled", 100L to "failed")
        assertThat(snap.disputeStatus).isEqualTo("lost")
    }

    @Test
    fun `an unknown charge is resource_missing and only charges with a refund or dispute are listed`() {
        assertThat(catchThrowableOfType(StripeResourceMissingException::class.java) { fake.retrieveCharge("ch_x") }.resource)
            .isEqualTo(Resource.CHARGE)
        fake.addCharge("ch_a", "pi_a", "A", 100)
        fake.addCharge("ch_b", "pi_b", "B", 100)
        fake.addCharge("ch_c", "pi_c", "C", 100)
        fake.refund("ch_a", 100)
        fake.openDispute("ch_b")
        assertThat(fake.listRecentChargeIdsWithRefundsOrDisputes(0).toList()).containsExactlyInAnyOrder("ch_a", "ch_b")
        fake.reset()
        assertThat(fake.listRecentChargeIdsWithRefundsOrDisputes(0).toList()).isEmpty()
    }
}
