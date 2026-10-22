package com.werewolf.unit.model

import com.werewolf.model.PlayerRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PlayerRoleTest {

    @Test
    fun `only WEREWOLF, WHITE_WOLF_KING and BLACK_WOLF_KING are in the wolf camp`() {
        assertThat(PlayerRole.entries.filter { it.isWolf })
            .containsExactlyInAnyOrder(PlayerRole.WEREWOLF, PlayerRole.WHITE_WOLF_KING, PlayerRole.BLACK_WOLF_KING)
    }

    @Test
    fun `only HUNTER and BLACK_WOLF_KING shoot on death`() {
        assertThat(PlayerRole.entries.filter { it.shootsOnDeath })
            .containsExactlyInAnyOrder(PlayerRole.HUNTER, PlayerRole.BLACK_WOLF_KING)
    }

    @Test
    fun `BLACK_WOLF_KING is not a god`() {
        assertThat(PlayerRole.BLACK_WOLF_KING.isGod).isFalse()
    }
}
