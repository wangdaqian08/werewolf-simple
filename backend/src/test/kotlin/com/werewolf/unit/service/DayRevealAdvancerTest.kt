package com.werewolf.unit.service

import com.werewolf.game.phase.DayRevealAdvancer
import com.werewolf.model.DaySubPhase
import com.werewolf.model.Game
import com.werewolf.model.GamePlayer
import com.werewolf.model.NightPhase
import com.werewolf.model.PlayerRole
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.util.Optional

/**
 * Unit coverage for the day-reveal decisions: the public sub-phase (an
 * unhandled dead sheriff hands over the badge, otherwise the day reveals its
 * result) and the private night-death shot (a wolf-killed, not poisoned
 * HUNTER or BLACK_WOLF_KING). A pending shot never shows in the sub-phase.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DayRevealAdvancerTest {

    @Mock
    lateinit var gameRepository: GameRepository
    @Mock
    lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock
    lateinit var nightPhaseRepository: NightPhaseRepository

    private val advancer by lazy { DayRevealAdvancer(gameRepository, gamePlayerRepository, nightPhaseRepository) }

    private val gameId = 1
    private val day = 2

    private fun game(
        sheriff: String? = null,
        selfDestruct: String? = null,
        taken: String? = null,
    ) = Game(roomId = 1, hostUserId = "h").also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.dayNumber = day
        it.sheriffUserId = sheriff
        it.selfDestructUserId = selfDestruct
        it.selfDestructTakenUserId = taken
    }

    private fun player(id: String, role: PlayerRole = PlayerRole.VILLAGER, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = id, seatIndex = 0, role = role).also { it.alive = alive }

    private fun night(
        wolf: String? = null,
        poison: String? = null,
        antidote: Boolean = false,
        guard: String? = null,
    ) = NightPhase(gameId = gameId, dayNumber = day).also {
        it.wolfTargetUserId = wolf
        it.witchPoisonTargetUserId = poison
        it.witchAntidoteUsed = antidote
        it.guardTargetUserId = guard
    }

    private fun stub(game: Game, players: List<GamePlayer>, night: NightPhase?) {
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(players)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.ofNullable(night))
    }

    // ── Public sub-phase ─────────────────────────────────────────────────────

    @Test
    fun `RESULT_REVEALED when nothing is pending`() {
        stub(game(), listOf(player("a")), night())
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED)
    }

    @Test
    fun `BADGE_HANDOVER when the sheriff is dead`() {
        stub(game(sheriff = "s"), listOf(player("s", alive = false)), night())
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.BADGE_HANDOVER)
    }

    @Test
    fun `a wolf-killed hunter's shot does not change the public sub-phase`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(wolf = "h"))
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED)
        assertThat(advancer.nightShooterUserId(gameId)).isEqualTo("h")
    }

    @Test
    fun `a hunter who is the dead sheriff - the public sub-phase is the badge handover`() {
        stub(game(sheriff = "h"), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(wolf = "h"))
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.BADGE_HANDOVER)
        assertThat(advancer.nightShooterUserId(gameId)).isEqualTo("h")
    }

    @Test
    fun `RESULT_REVEALED (safe default) when the game cannot be loaded`() {
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.empty())
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED)
        assertThat(advancer.nightShooterUserId(gameId)).isNull()
    }

    // ── Private night-death shot ─────────────────────────────────────────────

    @Test
    fun `no shot when the hunter died from poison only`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(poison = "h"))
        assertThat(advancer.nightShooterUserId(gameId)).isNull()
    }

    @Test
    fun `no shot when the hunter is both wolf-targeted and poisoned`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(wolf = "h", poison = "h"))
        assertThat(advancer.nightShooterUserId(gameId)).isNull()
    }

    @Test
    fun `no shot when the wolf-killed hunter was guard-protected (not actually killed)`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(wolf = "h", guard = "h"))
        assertThat(advancer.nightShooterUserId(gameId)).isNull()
    }

    @Test
    fun `no shot when the wolf attack was antidote-saved`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(wolf = "h", antidote = true))
        assertThat(advancer.nightShooterUserId(gameId)).isNull()
    }

    @Test
    fun `a shot when the hunter died to 同守同救 (guard + antidote)`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(wolf = "h", guard = "h", antidote = true))
        assertThat(advancer.nightShooterUserId(gameId)).isEqualTo("h")
    }

    @Test
    fun `no shot when the wolf-targeted hunter is still alive (e g night-1 immunity perk)`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = true)), night(wolf = "h"))
        assertThat(advancer.nightShooterUserId(gameId)).isNull()
    }

    @Test
    fun `no shot when the wolf-killed player is not a shooter`() {
        stub(game(), listOf(player("v", PlayerRole.VILLAGER, alive = false)), night(wolf = "v"))
        assertThat(advancer.nightShooterUserId(gameId)).isNull()
    }

    @Test
    fun `no shot when there is no night phase row`() {
        stub(game(), listOf(player("a")), null)
        assertThat(advancer.nightShooterUserId(gameId)).isNull()
    }

    @Test
    fun `a self-knifed BLACK_WOLF_KING gets the shot`() {
        stub(game(), listOf(player("k", PlayerRole.BLACK_WOLF_KING, alive = false)), night(wolf = "k"))
        assertThat(advancer.nightShooterUserId(gameId)).isEqualTo("k")
    }

    @Test
    fun `no shot when the wolf-killed BLACK_WOLF_KING was also poisoned`() {
        stub(game(), listOf(player("k", PlayerRole.BLACK_WOLF_KING, alive = false)), night(wolf = "k", poison = "k"))
        assertThat(advancer.nightShooterUserId(gameId)).isNull()
    }

    // Night victims are still alive during the sheriff campaign, so they can 自爆 / be taken there.
    @Test
    fun `no shot when the knifed BLACK_WOLF_KING self-destructed in the sheriff campaign`() {
        stub(game(selfDestruct = "k"), listOf(player("k", PlayerRole.BLACK_WOLF_KING, alive = false)), night(wolf = "k"))
        assertThat(advancer.nightShooterUserId(gameId)).isNull()
    }

    @Test
    fun `no shot when the knifed hunter was taken by the White Wolf King in the sheriff campaign`() {
        stub(game(selfDestruct = "w", taken = "h"), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(wolf = "h"))
        assertThat(advancer.nightShooterUserId(gameId)).isNull()
    }
}
