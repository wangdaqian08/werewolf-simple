package com.werewolf.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Test-seeded room codes must stay distinct for a whole JVM run and never look like real ones. */
class SeededRoomCodeTest {

    private val codes = (0 until 5000).map { TestConstants.seededRoomCode(it) }

    @Test
    fun `five thousand seeds get five thousand different codes`() {
        assertThat(codes.toSet()).hasSize(5000)
    }

    @Test
    fun `codes are three letters, never digits like real codes, never the invalid ZZZ`() {
        assertThat(codes).allMatch { it.matches(Regex("[A-Y]{3}")) }
        assertThat(codes).doesNotContain(TestConstants.INVALID_ROOM_CODE)
    }
}
