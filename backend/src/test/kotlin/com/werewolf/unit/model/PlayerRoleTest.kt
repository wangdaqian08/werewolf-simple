package com.werewolf.unit.model

import com.werewolf.model.PlayerRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PlayerRoleTest {

    @Test
    fun `only WEREWOLF and WHITE_WOLF_KING are in the wolf camp`() {
        assertThat(PlayerRole.entries.filter { it.isWolf })
            .containsExactlyInAnyOrder(PlayerRole.WEREWOLF, PlayerRole.WHITE_WOLF_KING)
    }
}
