package com.werewolf.unit.config

import com.werewolf.config.E2ePerkSeeder
import com.werewolf.model.Perk
import com.werewolf.repository.PerkRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.boot.DefaultApplicationArguments

class E2ePerkSeederTest {

    @Test
    fun `inserts only the missing catalog rows, with the production prices (V19, V36)`() {
        val repo = mock<PerkRepository> {
            on { existsById(any()) } doReturn false
            on { existsById("NIGHT1_IMMUNITY") } doReturn true
        }

        E2ePerkSeeder(repo).run(DefaultApplicationArguments())

        val saved = argumentCaptor<Perk>()
        verify(repo, times(3)).save(saved.capture())
        assertThat(saved.allValues.associate { it.perkCode to it.priceCredits }).isEqualTo(
            mapOf("ROLE_PICK_WOLF" to 30, "ROLE_PICK_GOD" to 25, "ROLE_PICK_VILLAGER" to 10),
        )
    }
}
