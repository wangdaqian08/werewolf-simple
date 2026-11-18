package com.werewolf.unit.role

import com.werewolf.game.role.MechNightSkill
import com.werewolf.game.role.RoleRules
import com.werewolf.model.GamePlayer
import com.werewolf.model.NightPhase
import com.werewolf.model.PlayerRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RoleRulesTest {

    private fun player(role: PlayerRole, seat: Int = 0, learned: PlayerRole? = null, alive: Boolean = true) =
        GamePlayer(gameId = 1, userId = "u$seat", seatIndex = seat, role = role, alive = alive, learnedRole = learned)

    private fun night(day: Int, learn: String? = null, poison: String? = null, guard: String? = null) =
        NightPhase(gameId = 1, dayNumber = day, mechLearnTargetUserId = learn, mechPoisonTargetUserId = poison, mechGuardTargetUserId = guard)

    @Test
    fun `shootsOnDeath covers learned hunter and learned black wolf king`() {
        assertThat(RoleRules.shootsOnDeath(player(PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.HUNTER))).isTrue()
        assertThat(RoleRules.shootsOnDeath(player(PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.BLACK_WOLF_KING))).isTrue()
        assertThat(RoleRules.shootsOnDeath(player(PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.SEER))).isFalse()
        assertThat(RoleRules.shootsOnDeath(player(PlayerRole.MECHANICAL_WOLF))).isFalse()
        assertThat(RoleRules.shootsOnDeath(player(PlayerRole.HUNTER))).isTrue()
    }

    @Test
    fun `mediumSees shows the learned role once learned, else the own role`() {
        assertThat(RoleRules.mediumSees(player(PlayerRole.MECHANICAL_WOLF))).isEqualTo(PlayerRole.MECHANICAL_WOLF)
        assertThat(RoleRules.mediumSees(player(PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.WITCH))).isEqualTo(PlayerRole.WITCH)
        assertThat(RoleRules.mediumSees(player(PlayerRole.GUARD))).isEqualTo(PlayerRole.GUARD)
    }

    @Test
    fun `wolfKillers is the pack, or the mechanical wolf when it is the only wolf`() {
        val mech = player(PlayerRole.MECHANICAL_WOLF, 1)
        val wolf = player(PlayerRole.WEREWOLF, 2)
        val good = player(PlayerRole.SEER, 3)
        assertThat(RoleRules.wolfKillers(listOf(mech, wolf, good))).containsExactly(wolf)
        assertThat(RoleRules.wolfKillers(listOf(mech, good))).containsExactly(mech)
        assertThat(RoleRules.wolfKillers(listOf(good))).isEmpty()
    }

    @Test
    fun `G8 - wolfKillers is the gargoyle only once it is the only wolf alive`() {
        val gargoyle = player(PlayerRole.GARGOYLE, 1)
        val wolf = player(PlayerRole.WEREWOLF, 2)
        val king = player(PlayerRole.WHITE_WOLF_KING, 3)
        val good = player(PlayerRole.SEER, 4)
        assertThat(RoleRules.wolfKillers(listOf(gargoyle, wolf, good))).containsExactly(wolf)
        assertThat(RoleRules.wolfKillers(listOf(gargoyle, king, good))).containsExactly(king)
        assertThat(RoleRules.wolfKillers(listOf(gargoyle, good))).containsExactly(gargoyle)
    }

    @Test
    fun `mechNightSkill is LEARN until a role is learned`() {
        assertThat(RoleRules.mechNightSkill(player(PlayerRole.MECHANICAL_WOLF), 1, emptyList())).isEqualTo(MechNightSkill.LEARN)
    }

    @Test
    fun `mechNightSkill is NONE on the learning night even after learning`() {
        val mech = player(PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.WITCH)
        assertThat(RoleRules.mechNightSkill(mech, 1, listOf(night(1, learn = "u5")))).isEqualTo(MechNightSkill.NONE)
    }

    @Test
    fun `learned witch poisons the next night, then never again`() {
        val mech = player(PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.WITCH)
        val n1 = night(1, learn = "u5")
        assertThat(RoleRules.mechNightSkill(mech, 2, listOf(n1, night(2)))).isEqualTo(MechNightSkill.POISON)
        assertThat(RoleRules.mechNightSkill(mech, 3, listOf(n1, night(2, poison = "u7"), night(3)))).isEqualTo(MechNightSkill.NONE)
    }

    @Test
    fun `learned guard protects the next night, then never again`() {
        val mech = player(PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.GUARD)
        val n1 = night(1, learn = "u5")
        assertThat(RoleRules.mechNightSkill(mech, 1, listOf(n1))).isEqualTo(MechNightSkill.NONE)
        assertThat(RoleRules.mechNightSkill(mech, 2, listOf(n1, night(2)))).isEqualTo(MechNightSkill.PROTECT)
        assertThat(RoleRules.mechNightSkill(mech, 3, listOf(n1, night(2, guard = "u7"), night(3)))).isEqualTo(MechNightSkill.NONE)
    }

    @Test
    fun `learned medium and seer check every night after learning`() {
        val n1 = night(1, learn = "u5")
        val medium = player(PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.MEDIUM)
        val seer = player(PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.SEER)
        listOf(2, 3).forEach {
            val nights = listOf(n1) + (2..it).map { d -> night(d) }
            assertThat(RoleRules.mechNightSkill(medium, it, nights)).isEqualTo(MechNightSkill.CHECK_ROLE)
            assertThat(RoleRules.mechNightSkill(seer, it, nights)).isEqualTo(MechNightSkill.CHECK_ALIGNMENT)
        }
    }

    @Test
    fun `learned hunter has no night skill`() {
        val mech = player(PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.HUNTER)
        assertThat(RoleRules.mechNightSkill(mech, 2, listOf(night(1, learn = "u5"), night(2)))).isEqualTo(MechNightSkill.NONE)
    }

    @Test
    fun `mechanical wolf learning KNIGHT gets no skill`() {
        val mech = player(PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.KNIGHT)
        val n1 = night(1, learn = "u5")
        assertThat(RoleRules.mechNightSkill(mech, 2, listOf(n1, night(2)))).isEqualTo(MechNightSkill.NONE)
        assertThat(RoleRules.shootsOnDeath(mech)).isFalse()
    }

    @Test
    fun `mechanical wolf learning GRAVEKEEPER gets no skill`() {
        val mech = player(PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.GRAVEKEEPER)
        val n1 = night(1, learn = "u5")
        assertThat(RoleRules.mechNightSkill(mech, 2, listOf(n1, night(2)))).isEqualTo(MechNightSkill.NONE)
        assertThat(RoleRules.shootsOnDeath(mech)).isFalse()
    }

    @Test
    fun `mechNightSkill fails loudly when a learned role has no learn night`() {
        val mech = player(PlayerRole.MECHANICAL_WOLF, learned = PlayerRole.WITCH)
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            RoleRules.mechNightSkill(mech, 2, listOf(night(1), night(2)))
        }
    }

    // ── 殉情 (W5, W6) ─────────────────────────────────────────────────────────

    private fun charmNight(day: Int, charm: String?) = NightPhase(gameId = 1, dayNumber = day).also { it.wolfBeautyCharmUserId = charm }

    @Test
    fun `charmVictim - the 狼美人 going out takes today's charmed player`() {
        val players = listOf(player(PlayerRole.WOLF_BEAUTY, 1), player(PlayerRole.SEER, 2))
        assertThat(RoleRules.charmVictim("u1", players, charmNight(2, "u2"))).isEqualTo("u2")
    }

    @Test
    fun `charmVictim - nobody when another player goes out`() {
        val players = listOf(player(PlayerRole.WOLF_BEAUTY, 1), player(PlayerRole.WEREWOLF, 2), player(PlayerRole.SEER, 3))
        assertThat(RoleRules.charmVictim("u2", players, charmNight(2, "u3"))).isNull()
    }

    @Test
    fun `charmVictim - nobody with no charm today, or no night row`() {
        val players = listOf(player(PlayerRole.WOLF_BEAUTY, 1), player(PlayerRole.SEER, 2))
        assertThat(RoleRules.charmVictim("u1", players, charmNight(2, null))).isNull()
        assertThat(RoleRules.charmVictim("u1", players, null)).isNull()
    }

    @Test
    fun `W5 - charmVictim ignores an earlier night's charm`() {
        // Night 2 charmed u2; night 3 (today's row) charmed nobody.
        val players = listOf(player(PlayerRole.WOLF_BEAUTY, 1), player(PlayerRole.SEER, 2))
        assertThat(RoleRules.charmVictim("u1", players, charmNight(3, null))).isNull()
    }

    @Test
    fun `charmVictim - nobody when the charmed player is already dead`() {
        val players = listOf(player(PlayerRole.WOLF_BEAUTY, 1), player(PlayerRole.SEER, 2, alive = false))
        assertThat(RoleRules.charmVictim("u1", players, charmNight(2, "u2"))).isNull()
    }

    @Test
    fun `charmVictimAtNight - the victim when the 狼美人 is among the night's deaths`() {
        val players = listOf(player(PlayerRole.WOLF_BEAUTY, 1), player(PlayerRole.SEER, 2), player(PlayerRole.VILLAGER, 3))
        assertThat(RoleRules.charmVictimAtNight(charmNight(2, "u2"), players, listOf("u1", "u3"))).isEqualTo("u2")
        assertThat(RoleRules.charmVictimAtNight(charmNight(2, "u2"), players, listOf("u3"))).isNull()
    }

    @Test
    fun `charmVictimAtNight - counts a player who died that night as alive (dawn list recomputed after the kills)`() {
        val beauty = player(PlayerRole.WOLF_BEAUTY, 1, alive = false).also { it.diedDay = 2 }
        val victim = player(PlayerRole.SEER, 2, alive = false).also { it.diedDay = 2 }
        val earlier = player(PlayerRole.VILLAGER, 3, alive = false).also { it.diedDay = 1 }
        assertThat(RoleRules.charmVictimAtNight(charmNight(2, "u2"), listOf(beauty, victim, earlier), listOf("u1", "u2"))).isEqualTo("u2")
        // Died before night 2 began: no 殉情.
        assertThat(RoleRules.charmVictimAtNight(charmNight(2, "u3"), listOf(beauty, victim, earlier), listOf("u1"))).isNull()
    }
}
