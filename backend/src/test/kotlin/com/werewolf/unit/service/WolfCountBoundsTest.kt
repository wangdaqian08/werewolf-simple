package com.werewolf.unit.service

import com.werewolf.service.WolfCountBounds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WolfCountBoundsTest {

    @Test
    fun `bounds table matches canonical floor(N÷3) plus-minus-one rule`() {
        val expected = mapOf(
            6 to Triple(1, 3, 2),
            7 to Triple(1, 3, 2),
            8 to Triple(1, 3, 2),
            9 to Triple(2, 4, 3),
            10 to Triple(2, 4, 3),
            11 to Triple(2, 4, 3),
            12 to Triple(3, 5, 4),
            15 to Triple(4, 6, 5),
        )
        expected.forEach { (n, triple) ->
            val (min, max, default) = triple
            assertThat(WolfCountBounds.min(n)).`as`("min($n)").isEqualTo(min)
            assertThat(WolfCountBounds.max(n)).`as`("max($n)").isEqualTo(max)
            assertThat(WolfCountBounds.default(n)).`as`("default($n)").isEqualTo(default)
        }
    }

    @Test
    fun `isValid accepts values inside the range and rejects outside`() {
        assertThat(WolfCountBounds.isValid(6, 1)).isTrue()
        assertThat(WolfCountBounds.isValid(6, 2)).isTrue()
        assertThat(WolfCountBounds.isValid(6, 3)).isTrue()
        assertThat(WolfCountBounds.isValid(6, 4)).isFalse()
        assertThat(WolfCountBounds.isValid(9, 1)).isFalse()
        assertThat(WolfCountBounds.isValid(9, 2)).isTrue()
        assertThat(WolfCountBounds.isValid(9, 3)).isTrue()
        assertThat(WolfCountBounds.isValid(12, 2)).isFalse()
        assertThat(WolfCountBounds.isValid(12, 5)).isTrue()
        assertThat(WolfCountBounds.isValid(12, 6)).isFalse()
    }
}
