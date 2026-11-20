package com.werewolf.unit.service

import com.werewolf.model.PaymentOrderStatus
import com.werewolf.service.ChargeSnapshot
import com.werewolf.service.DisputePhase
import com.werewolf.service.RefundInfo
import com.werewolf.service.moneyState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ChargeMoneyStateTest {

    /** [refunds] is "amount:status amount:status …" against a 1000 charge; blank = no refund. */
    private fun charge(refunds: String?, dispute: String?) = ChargeSnapshot(
        chargeId = "ch_1", paymentIntentId = "pi_1", orderNo = "o1", amount = 1000,
        refunds = refunds.orEmpty().split(' ').filter { it.isNotBlank() }.mapIndexed { i, r ->
            val (amount, status) = r.split(':')
            RefundInfo("re_$i", amount.toLong(), status)
        },
        disputeStatus = dispute,
    )

    @ParameterizedTest(name = "dispute={0} refunds={1} -> present={2} status={3} partial={4}")
    @CsvSource(
        // dispute status       refunds (charge 1000)        present status        partial
        "                      ,                            , true , COMPLETED   , false",
        "                      , 1000:succeeded             , false, REFUNDED    , false",
        "                      , 400:succeeded              , true , COMPLETED   , true",
        "                      , 400:succeeded 600:succeeded, false, REFUNDED    , true",
        "                      , 1000:failed                , true , COMPLETED   , false",
        "                      , 1000:pending               , false, REFUNDED    , false",
        "warning_needs_response,                            , false, DISPUTED    , false",
        "warning_needs_response, 1000:succeeded             , false, DISPUTED    , false",
        "warning_needs_response, 400:succeeded              , false, DISPUTED    , true",
        "warning_needs_response, 400:succeeded 600:succeeded, false, DISPUTED    , true",
        "warning_needs_response, 1000:failed                , false, DISPUTED    , false",
        "warning_needs_response, 1000:pending               , false, DISPUTED    , false",
        "warning_under_review  ,                            , false, DISPUTED    , false",
        "warning_under_review  , 1000:succeeded             , false, DISPUTED    , false",
        "warning_under_review  , 400:succeeded              , false, DISPUTED    , true",
        "warning_under_review  , 400:succeeded 600:succeeded, false, DISPUTED    , true",
        "warning_under_review  , 1000:failed                , false, DISPUTED    , false",
        "warning_under_review  , 1000:pending               , false, DISPUTED    , false",
        "needs_response        ,                            , false, DISPUTED    , false",
        "needs_response        , 1000:succeeded             , false, DISPUTED    , false",
        "needs_response        , 400:succeeded              , false, DISPUTED    , true",
        "needs_response        , 400:succeeded 600:succeeded, false, DISPUTED    , true",
        "needs_response        , 1000:failed                , false, DISPUTED    , false",
        "needs_response        , 1000:pending               , false, DISPUTED    , false",
        "under_review          ,                            , false, DISPUTED    , false",
        "under_review          , 1000:succeeded             , false, DISPUTED    , false",
        "under_review          , 400:succeeded              , false, DISPUTED    , true",
        "under_review          , 400:succeeded 600:succeeded, false, DISPUTED    , true",
        "under_review          , 1000:failed                , false, DISPUTED    , false",
        "under_review          , 1000:pending               , false, DISPUTED    , false",
        "won                   ,                            , true , COMPLETED   , false",
        "won                   , 1000:succeeded             , false, REFUNDED    , false",
        "won                   , 400:succeeded              , true , COMPLETED   , true",
        "won                   , 400:succeeded 600:succeeded, false, REFUNDED    , true",
        "won                   , 1000:failed                , true , COMPLETED   , false",
        "won                   , 1000:pending               , false, REFUNDED    , false",
        "lost                  ,                            , false, DISPUTE_LOST, false",
        "lost                  , 1000:succeeded             , false, DISPUTE_LOST, false",
        "lost                  , 400:succeeded              , false, DISPUTE_LOST, true",
        "lost                  , 400:succeeded 600:succeeded, false, DISPUTE_LOST, true",
        "lost                  , 1000:failed                , false, DISPUTE_LOST, false",
        "lost                  , 1000:pending               , false, DISPUTE_LOST, false",
        "warning_closed        ,                            , true , COMPLETED   , false",
        "warning_closed        , 1000:succeeded             , false, REFUNDED    , false",
        "warning_closed        , 400:succeeded              , true , COMPLETED   , true",
        "warning_closed        , 400:succeeded 600:succeeded, false, REFUNDED    , true",
        "warning_closed        , 1000:failed                , true , COMPLETED   , false",
        "warning_closed        , 1000:pending               , false, REFUNDED    , false",
        "brand_new_status      ,                            , false, DISPUTED    , false",
        "brand_new_status      , 1000:succeeded             , false, DISPUTED    , false",
        "brand_new_status      , 400:succeeded              , false, DISPUTED    , true",
        "brand_new_status      , 400:succeeded 600:succeeded, false, DISPUTED    , true",
        "brand_new_status      , 1000:failed                , false, DISPUTED    , false",
        "brand_new_status      , 1000:pending               , false, DISPUTED    , false",
    )
    fun `money state table`(dispute: String?, refunds: String?, present: Boolean, status: PaymentOrderStatus, partial: Boolean) {
        val s = moneyState(charge(refunds, dispute))

        assertThat(s.creditsShouldBePresent).isEqualTo(present)
        assertThat(s.orderStatus).isEqualTo(status)
        assertThat(s.partialRefundSeen).isEqualTo(partial)
    }

    @Test
    fun `dispute statuses map to phases`() {
        assertThat(moneyState(charge(null, null)).dispute).isEqualTo(DisputePhase.NONE)
        assertThat(moneyState(charge(null, "warning_needs_response")).dispute).isEqualTo(DisputePhase.OPEN)
        assertThat(moneyState(charge(null, "won")).dispute).isEqualTo(DisputePhase.WON)
        assertThat(moneyState(charge(null, "lost")).dispute).isEqualTo(DisputePhase.LOST)
        assertThat(moneyState(charge(null, "warning_closed")).dispute).isEqualTo(DisputePhase.INQUIRY_CLOSED)
        assertThat(moneyState(charge(null, "brand_new_status")).dispute).isEqualTo(DisputePhase.OPEN)
    }

    @Test
    fun `only succeeded and pending refunds count as money returned`() {
        val s = moneyState(charge("100:succeeded 200:pending 300:failed 400:canceled 500:requires_action", null))

        assertThat(s.refundedAmount).isEqualTo(300)
        assertThat(s.fullyRefunded).isFalse()
        assertThat(s.partialRefundSeen).isTrue()
    }

    @Test
    fun `a refund that requires action, was canceled or has no status is not money returned`() {
        listOf("1000:requires_action", "1000:canceled").forEach {
            val s = moneyState(charge(it, null))
            assertThat(s.refundedAmount).isZero()
            assertThat(s.creditsShouldBePresent).isTrue()
            assertThat(s.partialRefundSeen).isFalse()
        }
        val noStatus = ChargeSnapshot("ch_1", "pi_1", "o1", 1000, listOf(RefundInfo("re_1", 1000, null)), null)
        assertThat(moneyState(noStatus).creditsShouldBePresent).isTrue()
    }
}
