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
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.util.Optional

/**
 * Unit coverage for the day-reveal sub-phase decision that used to live inline
 * in GamePhasePipeline.revealNightResult. Priority: an unhandled dead sheriff
 * hands over the badge first, then a wolf-killed (not poisoned, not resolved)
 * hunter shoots, otherwise the day simply reveals its result.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DayRevealAdvancerTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository

    private val advancer by lazy { DayRevealAdvancer(gameRepository, gamePlayerRepository, nightPhaseRepository) }

    private val gameId = 1
    private val day = 2

    private fun game(sheriff: String? = null) = Game(roomId = 1, hostUserId = "h").also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.dayNumber = day
        it.sheriffUserId = sheriff
    }

    private fun player(id: String, role: PlayerRole = PlayerRole.VILLAGER, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = id, seatIndex = 0, role = role).also { it.alive = alive }

    private fun night(
        wolf: String? = null,
        poison: String? = null,
        antidote: Boolean = false,
        guard: String? = null,
        resolved: Boolean = false,
    ) = NightPhase(gameId = gameId, dayNumber = day).also {
        it.wolfTargetUserId = wolf
        it.witchPoisonTargetUserId = poison
        it.witchAntidoteUsed = antidote
        it.guardTargetUserId = guard
        it.hunterNightShootResolved = resolved
    }

    private fun stub(game: Game, players: List<GamePlayer>, night: NightPhase?) {
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(players)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.ofNullable(night))
    }

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
    fun `HUNTER_SHOOT_NIGHT_DEATH when a wolf-killed hunter is pending`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(wolf = "h"))
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH)
    }

    @Test
    fun `RESULT_REVEALED when the hunter died from poison only`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(poison = "h"))
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED)
    }

    @Test
    fun `RESULT_REVEALED when the hunter is both wolf-targeted and poisoned`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(wolf = "h", poison = "h"))
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED)
    }

    @Test
    fun `RESULT_REVEALED when the wolf-killed hunter was guard-protected (not actually killed)`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(wolf = "h", guard = "h"))
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED)
    }

    @Test
    fun `RESULT_REVEALED when the wolf attack was antidote-saved`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(wolf = "h", antidote = true))
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED)
    }

    @Test
    fun `BADGE_HANDOVER takes priority over a pending hunter shoot`() {
        stub(
            game(sheriff = "s"),
            listOf(player("s", alive = false), player("h", PlayerRole.HUNTER, alive = false)),
            night(wolf = "h"),
        )
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.BADGE_HANDOVER)
    }

    @Test
    fun `RESULT_REVEALED when the hunter shoot is already resolved`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(wolf = "h", resolved = true))
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED)
    }

    @Test
    fun `RESULT_REVEALED when the wolf-killed player is not a hunter`() {
        stub(game(), listOf(player("v", PlayerRole.VILLAGER, alive = false)), night(wolf = "v"))
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED)
    }

    @Test
    fun `pendingHunterUserId returns the eligible hunter id`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(wolf = "h"))
        assertThat(advancer.pendingHunterUserId(gameId)).isEqualTo("h")
    }

    @Test
    fun `pendingHunterUserId is null when the hunter was poisoned`() {
        stub(game(), listOf(player("h", PlayerRole.HUNTER, alive = false)), night(wolf = "h", poison = "h"))
        assertThat(advancer.pendingHunterUserId(gameId)).isNull()
    }

    @Test
    fun `RESULT_REVEALED when there is no night phase row`() {
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game()))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(listOf(player("a")))
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.empty())
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED)
    }

    @Test
    fun `RESULT_REVEALED (safe default) when the game cannot be loaded`() {
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.empty())
        assertThat(advancer.nextSubPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED)
    }

    @Test
    fun `markHunterShootResolved flips the flag and saves the night phase`() {
        val n = night(wolf = "h")
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game()))
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(n))

        advancer.markHunterShootResolved(gameId)

        assertThat(n.hunterNightShootResolved).isTrue()
        verify(nightPhaseRepository).save(n)
    }
}
