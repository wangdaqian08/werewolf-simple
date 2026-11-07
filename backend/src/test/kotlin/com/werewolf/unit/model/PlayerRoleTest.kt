package com.werewolf.unit.model

import com.werewolf.model.LearnedSkill
import com.werewolf.model.PlayerRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PlayerRoleTest {

    @Test
    fun `only WEREWOLF, the two wolf kings and MECHANICAL_WOLF are in the wolf camp`() {
        assertThat(PlayerRole.entries.filter { it.isWolf })
            .containsExactlyInAnyOrder(
                PlayerRole.WEREWOLF, PlayerRole.WHITE_WOLF_KING, PlayerRole.BLACK_WOLF_KING, PlayerRole.MECHANICAL_WOLF,
            )
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

    @Test
    fun `the gods are SEER, WITCH, HUNTER, GUARD, IDIOT, MEDIUM and KNIGHT - the villager is not a god`() {
        assertThat(PlayerRole.entries.filter { it.isGod })
            .containsExactlyInAnyOrder(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER, PlayerRole.GUARD, PlayerRole.IDIOT, PlayerRole.MEDIUM, PlayerRole.KNIGHT)
    }

    @Test
    fun `KNIGHT is a god with no learned skill`() {
        val knight = PlayerRole.KNIGHT
        assertThat(knight.isGod).isTrue()
        assertThat(knight.isWolf).isFalse()
        assertThat(knight.isPackWolf).isFalse()
        assertThat(knight.shootsOnDeath).isFalse()
        assertThat(knight.learnedSkill).isEqualTo(LearnedSkill.NONE)
    }

    @Test
    fun `isPackWolf excludes only the mechanical wolf`() {
        assertThat(PlayerRole.entries.filter { it.isPackWolf })
            .containsExactlyInAnyOrder(PlayerRole.WEREWOLF, PlayerRole.WHITE_WOLF_KING, PlayerRole.BLACK_WOLF_KING)
    }

    @Test
    fun `learnedSkill table`() {
        assertThat(PlayerRole.MEDIUM.learnedSkill).isEqualTo(LearnedSkill.CHECK_ROLE)
        assertThat(PlayerRole.SEER.learnedSkill).isEqualTo(LearnedSkill.CHECK_ALIGNMENT)
        assertThat(PlayerRole.WITCH.learnedSkill).isEqualTo(LearnedSkill.POISON)
        assertThat(PlayerRole.GUARD.learnedSkill).isEqualTo(LearnedSkill.PROTECT)
        assertThat(PlayerRole.HUNTER.learnedSkill).isEqualTo(LearnedSkill.SHOOT)
        assertThat(PlayerRole.BLACK_WOLF_KING.learnedSkill).isEqualTo(LearnedSkill.SHOOT)
        listOf(PlayerRole.VILLAGER, PlayerRole.IDIOT, PlayerRole.WEREWOLF, PlayerRole.WHITE_WOLF_KING, PlayerRole.MECHANICAL_WOLF)
            .forEach { assertThat(it.learnedSkill).isEqualTo(LearnedSkill.NONE) }
    }
}
