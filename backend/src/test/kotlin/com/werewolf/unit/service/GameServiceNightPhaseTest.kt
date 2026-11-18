package com.werewolf.unit.service

import com.werewolf.audio.AudioReplayCache
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.timer.HostTimerService
import com.werewolf.game.timer.TimerSnapshot
import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.GameService
import com.werewolf.service.SheriffService
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.util.*

@ExtendWith(MockitoExtension::class)
@Suppress("UNCHECKED_CAST")
class GameServiceNightPhaseTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var roomRepository: RoomRepository
    @Mock lateinit var roomPlayerRepository: RoomPlayerRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var nightOrchestrator: NightOrchestrator
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var sheriffService: SheriffService
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var voteRepository: VoteRepository
    @Mock lateinit var eliminationHistoryRepository: EliminationHistoryRepository
    @Mock lateinit var audioReplayCache: AudioReplayCache
    @Mock lateinit var hostTimerService: HostTimerService
    @Mock lateinit var creditTransactionRepository: CreditTransactionRepository
    @Mock lateinit var walletService: com.werewolf.service.WalletService
    @Mock lateinit var perkService: com.werewolf.service.PerkService
    @InjectMocks lateinit var gameService: GameService

    private val gameId = 1
    private val day = 2

    private fun game(phase: GamePhase = GamePhase.NIGHT) = Game(roomId = 1, hostUserId = "u1").also { g ->
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(g, gameId)
        g.phase = phase
        g.dayNumber = day
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = "u1", totalPlayers = 6, hasSheriff = false)

    private fun player(userId: String, seat: Int, role: PlayerRole) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role)

    private fun user(userId: String, nick: String) = User(userId = userId, nickname = nick)

    private fun nightPhase(
        subPhase: NightSubPhase = NightSubPhase.WEREWOLF_PICK,
        wolfTarget: String? = null,
        seerChecked: String? = null,
        seerIsWolf: Boolean? = null,
        antidoteUsed: Boolean = false,
        poisonTarget: String? = null,
        prevGuardTarget: String? = null,
        mediumChecked: String? = null,
        mediumRole: PlayerRole? = null,
    ) = NightPhase(
        gameId = gameId,
        dayNumber = day,
        subPhase = subPhase,
        wolfTargetUserId = wolfTarget,
        seerCheckedUserId = seerChecked,
        seerResultIsWerewolf = seerIsWolf,
        witchAntidoteUsed = antidoteUsed,
        witchPoisonTargetUserId = poisonTarget,
        prevGuardTargetUserId = prevGuardTarget,
        mediumCheckedUserId = mediumChecked,
        mediumResultRole = mediumRole,
    )

    @BeforeEach
    fun setupCommon() {
        whenever(roomRepository.findById(1)).thenReturn(Optional.of(room()))
        whenever(hostTimerService.snapshot(any())).thenReturn(TimerSnapshot(0L, 0L, false))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun setupGameAndPlayers(
        game: Game,
        players: List<GamePlayer>,
        users: List<User>,
        nightSubPhase: NightSubPhase? = null
    ) {
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(players)
        whenever(userRepository.findAllById(players.map { it.userId })).thenReturn(users)
    }

    @Suppress("UNCHECKED_CAST")
    private fun nightPhaseResult(result: Map<String, Any?>): Map<String, Any?> =
        result["nightPhase"] as Map<String, Any?>

    // ─────────────────────────────────────────────────────────────────────────
    // Bug 1 + 2 — WEREWOLF: player names + teammate list
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getGameState - players map always includes nickname`() {
        val players = listOf(
            player("u1", 1, PlayerRole.VILLAGER),
            player("u2", 2, PlayerRole.WEREWOLF),
        )
        val users = listOf(user("u1", "Alice"), user("u2", "Bob"))
        val game = game(phase = GamePhase.DAY_DISCUSSION).also { it.dayNumber = 1 }
        setupGameAndPlayers(game, players, users)

        val result = gameService.getGameState(gameId, "u1")

        @Suppress("UNCHECKED_CAST")
        val playerList = result["players"] as List<Map<String, Any?>>
        val alice = playerList.first { it["userId"] == "u1" }
        val bob   = playerList.first { it["userId"] == "u2" }
        assertThat(alice["nickname"]).isEqualTo("Alice")
        assertThat(bob["nickname"]).isEqualTo("Bob")
    }

    // The dashboard renders gameStore.state.players via v-for without any
    // client-side sort, so the contract is: getGameState must always return
    // players[] in ascending seatIndex order. Postgres returns rows in heap
    // order (which can shift after UPDATEs on GamePlayer.alive / .sheriff /
    // .confirmedRole), so the API has to sort. This test runs across EVERY
    // GamePhase to also catch a future refactor that branches the players-
    // list construction by phase and forgets to sort in some path.
    @ParameterizedTest
    @EnumSource(GamePhase::class)
    fun `getGameState - players list is always sorted by seatIndex regardless of phase`(phase: GamePhase) {
        val players = listOf(
            player("u3", 3, PlayerRole.VILLAGER),
            player("u1", 1, PlayerRole.VILLAGER),
            player("u4", 4, PlayerRole.VILLAGER),
            player("u2", 2, PlayerRole.WEREWOLF),
        )
        val users = listOf(user("u1", "A"), user("u2", "B"), user("u3", "C"), user("u4", "D"))
        val game = game(phase = phase).also { it.dayNumber = 1 }
        setupGameAndPlayers(game, players, users)

        val result = gameService.getGameState(gameId, "u1")

        @Suppress("UNCHECKED_CAST")
        val playerList = result["players"] as List<Map<String, Any?>>
        assertThat(playerList.map { it["seatIndex"] })
            .`as`("players[] must be in seat order during phase=$phase")
            .containsExactly(1, 2, 3, 4)
    }

    @Test
    fun `getGameState - WEREWOLF gets teammate list formatted as seat-dot-nick`() {
        val wolf1 = player("u1", 1, PlayerRole.WEREWOLF)
        val wolf2 = player("u2", 2, PlayerRole.WEREWOLF)
        val villager = player("u3", 3, PlayerRole.VILLAGER)
        val players = listOf(wolf1, wolf2, villager)
        val users = listOf(user("u1", "Wolf1"), user("u2", "Wolf2"), user("u3", "Dave"))
        val game = game()
        setupGameAndPlayers(game, players, users, NightSubPhase.WEREWOLF_PICK)

        val np = nightPhase(subPhase = NightSubPhase.WEREWOLF_PICK, wolfTarget = "u3")
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        @Suppress("UNCHECKED_CAST")
        val teammates = night["teammates"] as List<String>
        assertThat(teammates).containsExactly("2·Wolf2")
        assertThat(night["selectedTargetId"]).isEqualTo("u3")
    }

    @Test
    fun `getGameState - WHITE_WOLF_KING and WEREWOLF see each other as night teammates`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val king = player("u2", 2, PlayerRole.WHITE_WOLF_KING)
        val villager = player("u3", 3, PlayerRole.VILLAGER)
        val users = listOf(user("u1", "Wolf1"), user("u2", "King"), user("u3", "Dave"))
        setupGameAndPlayers(game(), listOf(wolf, king, villager), users, NightSubPhase.WEREWOLF_PICK)
        val np = nightPhase(subPhase = NightSubPhase.WEREWOLF_PICK)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        @Suppress("UNCHECKED_CAST")
        val kingTeammates = nightPhaseResult(gameService.getGameState(gameId, "u2"))["teammates"] as List<String>
        @Suppress("UNCHECKED_CAST")
        val wolfTeammates = nightPhaseResult(gameService.getGameState(gameId, "u1"))["teammates"] as List<String>
        assertThat(kingTeammates).containsExactly("1·Wolf1")
        assertThat(wolfTeammates).containsExactly("2·King")
    }

    @Test
    fun `getGameState - non-wolf player does NOT get teammates`() {
        val players = listOf(
            player("u1", 1, PlayerRole.VILLAGER),
            player("u2", 2, PlayerRole.WEREWOLF),
        )
        val users = listOf(user("u1", "Alice"), user("u2", "Bob"))
        val game = game()
        setupGameAndPlayers(game, players, users, NightSubPhase.WAITING)
        val np = nightPhase(subPhase = NightSubPhase.WAITING)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        assertThat(night.containsKey("teammates")).isFalse()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bug 3 — SEER: result card + history
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getGameState - SEER gets seerResult when check is complete`() {
        val seer = player("u1", 1, PlayerRole.SEER)
        val target = player("u3", 3, PlayerRole.WEREWOLF)
        val players = listOf(seer, target)
        val users = listOf(user("u1", "Seer"), user("u3", "Wolf"))
        val game = game()
        setupGameAndPlayers(game, players, users, NightSubPhase.SEER_RESULT)

        // current night: seer already checked u3
        val np = nightPhase(
            subPhase = NightSubPhase.SEER_RESULT,
            seerChecked = "u3",
            seerIsWolf = true,
        )
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        // history includes previous night (day 1) and current (day 2)
        val prevNight = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.seerCheckedUserId = "u3"
            it.seerResultIsWerewolf = true
        }
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(prevNight, np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        @Suppress("UNCHECKED_CAST")
        val seerResult = night["seerResult"] as Map<String, Any?>
        assertThat(seerResult["checkedPlayerId"]).isEqualTo("u3")
        assertThat(seerResult["checkedNickname"]).isEqualTo("Wolf")
        assertThat(seerResult["checkedSeatIndex"]).isEqualTo(3)
        assertThat(seerResult["isWerewolf"]).isEqualTo(true)

        @Suppress("UNCHECKED_CAST")
        val history = seerResult["history"] as List<Map<String, Any?>>
        assertThat(history).hasSize(2)
        assertThat(history[0]["round"]).isEqualTo(1)
        assertThat(history[0]["isWerewolf"]).isEqualTo(true)
    }

    @Test
    fun `medium state carries role and history`() {
        val medium = player("u1", 1, PlayerRole.MEDIUM)
        val target = player("u3", 3, PlayerRole.WITCH)
        val users = listOf(user("u1", "Medium"), user("u3", "Wendy"))
        setupGameAndPlayers(game(), listOf(medium, target), users, NightSubPhase.MEDIUM_RESULT)
        val np = nightPhase(subPhase = NightSubPhase.MEDIUM_RESULT, mediumChecked = "u3", mediumRole = PlayerRole.WITCH)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        val prev = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.mediumCheckedUserId = "u3"
            it.mediumResultRole = PlayerRole.WITCH
        }
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(prev, np))

        val night = nightPhaseResult(gameService.getGameState(gameId, "u1"))

        @Suppress("UNCHECKED_CAST")
        val mediumResult = night["mediumResult"] as Map<String, Any?>
        assertThat(mediumResult["checkedPlayerId"]).isEqualTo("u3")
        assertThat(mediumResult["checkedNickname"]).isEqualTo("Wendy")
        assertThat(mediumResult["checkedSeatIndex"]).isEqualTo(3)
        assertThat(mediumResult["role"]).isEqualTo("WITCH")
        @Suppress("UNCHECKED_CAST")
        val history = mediumResult["history"] as List<Map<String, Any?>>
        assertThat(history).hasSize(2)
        assertThat(history[0]["round"]).isEqualTo(1)
        assertThat(history[0]["nickname"]).isEqualTo("Wendy")
        assertThat(history[0]["role"]).isEqualTo("WITCH")
    }

    @Test
    fun `mechWolf state - skillTonight LEARN before learning, POISON the night after learning WITCH`() {
        val mech = player("u1", 1, PlayerRole.MECHANICAL_WOLF)
        val target = player("u3", 3, PlayerRole.WITCH)
        val users = listOf(user("u1", "Mech"), user("u3", "Wendy"))
        setupGameAndPlayers(game(), listOf(mech, target), users, NightSubPhase.MECH_WOLF_PICK)
        val np = nightPhase(subPhase = NightSubPhase.MECH_WOLF_PICK)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        val n1 = NightPhase(gameId = gameId, dayNumber = 1)
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(n1, np))

        var state = nightPhaseResult(gameService.getGameState(gameId, "u1"))["mechWolf"] as Map<String, Any?>
        assertThat(state["skillTonight"]).isEqualTo("LEARN")
        assertThat(state["learnedRole"]).isNull()
        assertThat(state["result"]).isNull()

        // Learned WITCH on night 1: tonight (night 2) it can poison.
        mech.learnedRole = PlayerRole.WITCH
        n1.mechLearnTargetUserId = "u3"
        state = nightPhaseResult(gameService.getGameState(gameId, "u1"))["mechWolf"] as Map<String, Any?>
        assertThat(state["skillTonight"]).isEqualTo("POISON")
        assertThat(state["learnedRole"]).isEqualTo("WITCH")
        assertThat(state["learnedPlayerId"]).isEqualTo("u3")
        assertThat(state["result"]).isNull()

        // Poisoned tonight: the result is the action taken.
        np.mechPoisonTargetUserId = "u3"
        val result = (nightPhaseResult(gameService.getGameState(gameId, "u1"))["mechWolf"] as Map<String, Any?>)["result"] as Map<String, Any?>
        assertThat(result["kind"]).isEqualTo("POISON")
        assertThat(result["targetPlayerId"]).isEqualTo("u3")
        assertThat(result["targetNickname"]).isEqualTo("Wendy")
        assertThat(result["targetSeatIndex"]).isEqualTo(3)
    }

    @Test
    fun `mechWolf state - a learned seer exposes only isWerewolf, SKIP shows in the result phase`() {
        val mech = player("u1", 1, PlayerRole.MECHANICAL_WOLF).also { it.learnedRole = PlayerRole.SEER }
        val target = player("u2", 2, PlayerRole.WEREWOLF)
        val users = listOf(user("u1", "Mech"), user("u2", "Wolfie"))
        setupGameAndPlayers(game(), listOf(mech, target), users, NightSubPhase.MECH_WOLF_RESULT)
        val np = nightPhase(subPhase = NightSubPhase.MECH_WOLF_RESULT)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        val n1 = NightPhase(gameId = gameId, dayNumber = 1).also { it.mechLearnTargetUserId = "u2" }
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(n1, np))

        var state = nightPhaseResult(gameService.getGameState(gameId, "u1"))["mechWolf"] as Map<String, Any?>
        assertThat((state["result"] as Map<String, Any?>)["kind"]).isEqualTo("SKIP")

        np.mechCheckTargetUserId = "u2"
        np.mechCheckResultRole = PlayerRole.WEREWOLF
        state = nightPhaseResult(gameService.getGameState(gameId, "u1"))["mechWolf"] as Map<String, Any?>
        val result = state["result"] as Map<String, Any?>
        assertThat(result["kind"]).isEqualTo("CHECK")
        assertThat(result["isWerewolf"]).isEqualTo(true)
        assertThat(result.containsKey("role")).isFalse()
    }

    @Test
    fun `pack wolves never get mechWolf`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val users = listOf(user("u1", "Wolf"))
        setupGameAndPlayers(game(), listOf(wolf), users, NightSubPhase.MECH_WOLF_PICK)
        val np = nightPhase(subPhase = NightSubPhase.MECH_WOLF_PICK)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        assertThat(nightPhaseResult(gameService.getGameState(gameId, "u1")).containsKey("mechWolf")).isFalse()
    }

    // ── GRAVEKEEPER: yesterday's vote-out, served from the stored night row ────

    private fun gravekeeperNight(result: GravekeeperResult?, exiled: String?) =
        nightPhase(subPhase = NightSubPhase.GRAVEKEEPER_RESULT).also {
            it.gravekeeperResult = result
            it.gravekeeperExiledUserId = exiled
        }

    @Test
    fun `state serves gravekeeperResult to the gravekeeper`() {
        // A page refresh reads this from GET /state: nothing is pushed and nothing lives in the client.
        val keeper = player("u1", 1, PlayerRole.GRAVEKEEPER)
        val exiled = player("u3", 3, PlayerRole.WEREWOLF).also { it.alive = false }
        setupGameAndPlayers(game(), listOf(keeper, exiled), listOf(user("u1", "Keeper"), user("u3", "Wendy")))
        val np = gravekeeperNight(GravekeeperResult.WOLF, "u3")
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        val night1 = NightPhase(gameId = gameId, dayNumber = 1).also { it.gravekeeperResult = GravekeeperResult.NONE }
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(np, night1))

        val night = nightPhaseResult(gameService.getGameState(gameId, "u1"))

        assertThat(night["gravekeeperResult"]).isEqualTo(
            mapOf(
                "result" to "WOLF",
                "exiledPlayerId" to "u3",
                "exiledNickname" to "Wendy",
                "exiledSeatIndex" to 3,
                "history" to listOf(mapOf("round" to 2, "nickname" to "Wendy", "isWerewolf" to true)),
            ),
        )
    }

    @Test
    fun `gravekeeperResult NONE carries null exile fields and an empty history`() {
        val keeper = player("u1", 1, PlayerRole.GRAVEKEEPER)
        setupGameAndPlayers(game(), listOf(keeper), listOf(user("u1", "Keeper")))
        val np = gravekeeperNight(GravekeeperResult.NONE, null)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(np))

        val night = nightPhaseResult(gameService.getGameState(gameId, "u1"))

        assertThat(night["gravekeeperResult"]).isEqualTo(
            mapOf(
                "result" to "NONE",
                "exiledPlayerId" to null,
                "exiledNickname" to null,
                "exiledSeatIndex" to null,
                "history" to emptyList<Any>(),
            ),
        )
    }

    @Test
    fun `non-gravekeeper state has no gravekeeperResult`() {
        // The exiled player's camp is the gravekeeper's alone.
        val keeper = player("u1", 1, PlayerRole.GRAVEKEEPER)
        val villager = player("u2", 2, PlayerRole.VILLAGER)
        val exiled = player("u3", 3, PlayerRole.WEREWOLF).also { it.alive = false }
        setupGameAndPlayers(game(), listOf(keeper, villager, exiled), listOf(user("u1", "Keeper"), user("u2", "Vic"), user("u3", "Wendy")))
        val np = gravekeeperNight(GravekeeperResult.WOLF, "u3")
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(np))

        val night = nightPhaseResult(gameService.getGameState(gameId, "u2"))

        assertThat(night.containsKey("gravekeeperResult")).isFalse()
        assertThat(gameService.getGameState(gameId, "u3").toString()).doesNotContain("gravekeeperResult")
    }

    @Test
    fun `dead gravekeeper's night state has no gravekeeperResult`() {
        // Night-init stores nothing for a dead gravekeeper, so there is nothing to serve.
        val keeper = player("u1", 1, PlayerRole.GRAVEKEEPER).also { it.alive = false }
        setupGameAndPlayers(game(), listOf(keeper), listOf(user("u1", "Keeper")))
        val np = gravekeeperNight(null, null)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        val night = nightPhaseResult(gameService.getGameState(gameId, "u1"))

        assertThat(night.containsKey("gravekeeperResult")).isFalse()
    }

    // ── GARGOYLE: private check state, served from the night rows ──────────────

    private fun gargoyleNights(tonight: NightPhase, vararg earlier: NightPhase) {
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(tonight))
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(tonight, *earlier))
    }

    private fun gargoyleCheck(dayNumber: Int, sub: NightSubPhase, target: String?, role: PlayerRole?) =
        NightPhase(gameId = gameId, dayNumber = dayNumber, subPhase = sub).also {
            it.gargoyleCheckedUserId = target
            it.gargoyleResultRole = role
        }

    private val gargoyleBoard = listOf(
        player("u1", 1, PlayerRole.GARGOYLE), player("u2", 2, PlayerRole.WEREWOLF),
        player("u3", 3, PlayerRole.SEER), player("u4", 4, PlayerRole.VILLAGER), player("u5", 5, PlayerRole.WHITE_WOLF_KING),
    )
    private val gargoyleUsers = listOf(user("u1", "Gary"), user("u2", "Wolf"), user("u3", "Sage"), user("u4", "Vic"), user("u5", "King"))

    @Test
    fun `G14 - before tonight's check the gargoyle sees earlier checks and no result`() {
        setupGameAndPlayers(game(), gargoyleBoard, gargoyleUsers)
        gargoyleNights(gargoyleCheck(day, NightSubPhase.GARGOYLE_PICK, null, null), gargoyleCheck(1, NightSubPhase.COMPLETE, "u3", PlayerRole.SEER))

        val night = nightPhaseResult(gameService.getGameState(gameId, "u1"))

        assertThat(night["gargoyle"]).isEqualTo(
            mapOf(
                "checkedPlayerIds" to listOf("u3"),
                "result" to null,
                "history" to listOf(mapOf("round" to 1, "nickname" to "Sage", "seatIndex" to 3, "role" to "SEER")),
            ),
        )
    }

    @Test
    fun `G3 - after a check the gargoyle sees the exact role, and checkedPlayerIds includes tonight's`() {
        setupGameAndPlayers(game(), gargoyleBoard, gargoyleUsers)
        gargoyleNights(
            gargoyleCheck(day, NightSubPhase.GARGOYLE_RESULT, "u5", PlayerRole.WHITE_WOLF_KING),
            gargoyleCheck(1, NightSubPhase.COMPLETE, "u3", PlayerRole.SEER),
        )

        @Suppress("UNCHECKED_CAST")
        val gargoyle = nightPhaseResult(gameService.getGameState(gameId, "u1"))["gargoyle"] as Map<String, Any?>

        assertThat(gargoyle["checkedPlayerIds"] as List<*>).containsExactly("u3", "u5")
        assertThat(gargoyle["result"]).isEqualTo(
            mapOf("kind" to "CHECK", "targetPlayerId" to "u5", "targetNickname" to "King", "targetSeatIndex" to 5, "role" to "WHITE_WOLF_KING"),
        )
        assertThat(gargoyle["history"] as List<*>).hasSize(2)
    }

    @Test
    fun `G5 - a skip shows as SKIP in GARGOYLE_RESULT`() {
        setupGameAndPlayers(game(), gargoyleBoard, gargoyleUsers)
        gargoyleNights(gargoyleCheck(day, NightSubPhase.GARGOYLE_RESULT, null, null))

        @Suppress("UNCHECKED_CAST")
        val gargoyle = nightPhaseResult(gameService.getGameState(gameId, "u1"))["gargoyle"] as Map<String, Any?>

        assertThat(gargoyle["result"]).isEqualTo(mapOf("kind" to "SKIP"))
        assertThat(gargoyle["checkedPlayerIds"] as List<*>).isEmpty()
    }

    @Test
    fun `G14 - the gargoyle state is served in every night sub-phase, e g during the witch's turn`() {
        setupGameAndPlayers(game(), gargoyleBoard, gargoyleUsers)
        gargoyleNights(gargoyleCheck(day, NightSubPhase.WITCH_ACT, "u4", PlayerRole.VILLAGER))

        val night = nightPhaseResult(gameService.getGameState(gameId, "u1"))

        assertThat(night).containsKey("gargoyle")
    }

    @Test
    fun `G2 - nobody but the gargoyle gets the gargoyle state`() {
        setupGameAndPlayers(game(), gargoyleBoard, gargoyleUsers)
        gargoyleNights(gargoyleCheck(day, NightSubPhase.GARGOYLE_RESULT, "u2", PlayerRole.WEREWOLF))

        for (viewer in listOf("u2", "u3", "u4", "u5")) {
            val state = gameService.getGameState(gameId, viewer)
            assertThat(nightPhaseResult(state)).`as`(viewer).doesNotContainKey("gargoyle")
            assertThat(state.toString()).`as`(viewer).doesNotContain("gargoyle")
        }
    }

    @Test
    fun `G2 G13 - the gargoyle and the pack are not teammates, even after it checked a wolf`() {
        setupGameAndPlayers(game(), gargoyleBoard, gargoyleUsers)
        // Tonight it checked the werewolf u2; the pack still does not list it, and it lists no one.
        gargoyleNights(gargoyleCheck(day, NightSubPhase.WEREWOLF_PICK, "u2", PlayerRole.WEREWOLF).also { it.wolfTargetUserId = "u4" })

        val gargoyleState = gameService.getGameState(gameId, "u1")
        val gargoyleNight = nightPhaseResult(gargoyleState)
        assertThat(gargoyleNight).doesNotContainKeys("teammates", "selectedTargetId")
        assertThat(gargoyleNight["wolfKiller"]).isEqualTo(false)
        assertThat(gargoyleState["wolfTeammates"] as List<*>).isEmpty()

        val packState = gameService.getGameState(gameId, "u2")
        @Suppress("UNCHECKED_CAST")
        assertThat(nightPhaseResult(packState)["teammates"] as List<String>).containsExactly("5·King")
        @Suppress("UNCHECKED_CAST")
        assertThat(packState["wolfTeammates"] as List<String>).containsExactly("u5")
    }

    @Test
    fun `G8 - the lone gargoyle is the wolf killer and sees the pick`() {
        val board = listOf(
            player("u1", 1, PlayerRole.GARGOYLE), player("u2", 2, PlayerRole.WEREWOLF).also { it.alive = false },
            player("u4", 4, PlayerRole.VILLAGER),
        )
        setupGameAndPlayers(game(), board, gargoyleUsers)
        gargoyleNights(gargoyleCheck(day, NightSubPhase.WEREWOLF_PICK, null, null).also { it.wolfTargetUserId = "u4" })

        val night = nightPhaseResult(gameService.getGameState(gameId, "u1"))

        assertThat(night["wolfKiller"]).isEqualTo(true)
        assertThat(night["selectedTargetId"]).isEqualTo("u4")
        assertThat(night).doesNotContainKey("teammates")
    }

    @Test
    fun `non-medium never gets mediumResult`() {
        val seer = player("u1", 1, PlayerRole.SEER)
        val target = player("u3", 3, PlayerRole.WITCH)
        val users = listOf(user("u1", "Seer"), user("u3", "Wendy"))
        setupGameAndPlayers(game(), listOf(seer, target), users, NightSubPhase.MEDIUM_RESULT)
        val np = nightPhase(subPhase = NightSubPhase.MEDIUM_RESULT, mediumChecked = "u3", mediumRole = PlayerRole.WITCH)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        val night = nightPhaseResult(gameService.getGameState(gameId, "u1"))

        assertThat(night.containsKey("mediumResult")).isFalse()
    }

    @Test
    fun `getGameState - SEER does NOT get seerResult before check is done`() {
        val seer = player("u1", 1, PlayerRole.SEER)
        val players = listOf(seer)
        val users = listOf(user("u1", "Seer"))
        val game = game()
        setupGameAndPlayers(game, players, users, NightSubPhase.SEER_PICK)
        val np = nightPhase(subPhase = NightSubPhase.SEER_PICK)  // no seerCheckedUserId
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        assertThat(night.containsKey("seerResult")).isFalse()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bug 4 — WITCH: antidote / poison availability + attack info
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getGameState - WITCH gets hasAntidote=true and hasPoison=true when unused`() {
        val witch = player("u1", 1, PlayerRole.WITCH)
        val attacked = player("u4", 4, PlayerRole.VILLAGER)
        val players = listOf(witch, attacked)
        val users = listOf(user("u1", "Witch"), user("u4", "Dave"))
        val game = game()
        setupGameAndPlayers(game, players, users, NightSubPhase.WITCH_ACT)

        val np = nightPhase(subPhase = NightSubPhase.WITCH_ACT, wolfTarget = "u4")
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        // no prior night, so no antidote/poison used ever
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        assertThat(night["hasAntidote"]).isEqualTo(true)
        assertThat(night["hasPoison"]).isEqualTo(true)
        assertThat(night["attackedPlayerId"]).isEqualTo("u4")
        assertThat(night["attackedNickname"]).isEqualTo("Dave")
        assertThat(night["attackedSeatIndex"]).isEqualTo(4)
    }

    @Test
    fun `getGameState - WITCH gets hasAntidote=false when used in a previous night`() {
        val witch = player("u1", 1, PlayerRole.WITCH)
        val attacked = player("u4", 4, PlayerRole.VILLAGER)
        val players = listOf(witch, attacked)
        val users = listOf(user("u1", "Witch"), user("u4", "Dave"))
        val game = game()
        setupGameAndPlayers(game, players, users, NightSubPhase.WITCH_ACT)

        val np = nightPhase(subPhase = NightSubPhase.WITCH_ACT, wolfTarget = "u4")
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        // night 1: antidote was used
        val night1 = NightPhase(gameId = gameId, dayNumber = 1).also { it.witchAntidoteUsed = true }
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(night1, np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        assertThat(night["hasAntidote"]).isEqualTo(false)
        assertThat(night["hasPoison"]).isEqualTo(true)  // poison not yet used
        // Without the antidote the witch is no longer told who the wolves attacked.
        assertThat(night.containsKey("attackedPlayerId")).isFalse()
        assertThat(night.containsKey("attackedSeatIndex")).isFalse()
    }

    @Test
    fun `getGameState - WITCH attacked after night 1 gets selfSaveBlocked=true`() {
        val witch = player("u1", 1, PlayerRole.WITCH)
        val players = listOf(witch, player("u4", 4, PlayerRole.VILLAGER))
        val users = listOf(user("u1", "Witch"), user("u4", "Dave"))
        setupGameAndPlayers(game(), players, users, NightSubPhase.WITCH_ACT)

        val np = nightPhase(subPhase = NightSubPhase.WITCH_ACT, wolfTarget = "u1")
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(np))

        val night = nightPhaseResult(gameService.getGameState(gameId, "u1"))

        assertThat(night["attackedPlayerId"]).isEqualTo("u1")
        assertThat(night["selfSaveBlocked"]).isEqualTo(true)
    }

    @Test
    fun `getGameState - WITCH attacked someone else gets selfSaveBlocked=false`() {
        val witch = player("u1", 1, PlayerRole.WITCH)
        val players = listOf(witch, player("u4", 4, PlayerRole.VILLAGER))
        val users = listOf(user("u1", "Witch"), user("u4", "Dave"))
        setupGameAndPlayers(game(), players, users, NightSubPhase.WITCH_ACT)

        val np = nightPhase(subPhase = NightSubPhase.WITCH_ACT, wolfTarget = "u4")
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(np))

        val night = nightPhaseResult(gameService.getGameState(gameId, "u1"))

        assertThat(night["selfSaveBlocked"]).isEqualTo(false)
    }

    @Test
    fun `getGameState - WITCH in WITCH_ACT sees only the wolf target, never the mech poison target`() {
        val players = listOf(
            player("u1", 1, PlayerRole.WITCH), player("u3", 3, PlayerRole.MECHANICAL_WOLF),
            player("u4", 4, PlayerRole.VILLAGER), player("u5", 5, PlayerRole.VILLAGER),
        )
        val users = listOf(user("u1", "Witch"), user("u3", "Mech"), user("u4", "Dave"), user("u5", "Eve"))
        setupGameAndPlayers(game(), players, users, NightSubPhase.WITCH_ACT)

        val np = nightPhase(subPhase = NightSubPhase.WITCH_ACT, wolfTarget = "u4").also { it.mechPoisonTargetUserId = "u5" }
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(np))

        val night = nightPhaseResult(gameService.getGameState(gameId, "u1"))

        assertThat(night["attackedPlayerId"]).isEqualTo("u4")
        assertThat(night.toString()).doesNotContain("u5").doesNotContain("Eve")
    }

    @Test
    fun `getGameState - WITCH gets hasPoison=false when poison used in a previous night`() {
        val witch = player("u1", 1, PlayerRole.WITCH)
        val players = listOf(witch)
        val users = listOf(user("u1", "Witch"))
        val game = game()
        setupGameAndPlayers(game, players, users, NightSubPhase.WITCH_ACT)

        val np = nightPhase(subPhase = NightSubPhase.WITCH_ACT)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))
        val night1 = NightPhase(gameId = gameId, dayNumber = 1).also { it.witchPoisonTargetUserId = "u5" }
        whenever(nightPhaseRepository.findByGameId(gameId)).thenReturn(listOf(night1, np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        assertThat(night["hasPoison"]).isEqualTo(false)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bug 5 — GUARD: previous target indicator
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getGameState - GUARD gets previousGuardTargetId`() {
        val guard = player("u1", 1, PlayerRole.GUARD)
        val players = listOf(guard)
        val users = listOf(user("u1", "Guard"))
        val game = game()
        setupGameAndPlayers(game, players, users, NightSubPhase.GUARD_PICK)

        val np = nightPhase(subPhase = NightSubPhase.GUARD_PICK, prevGuardTarget = "u4")
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        assertThat(night["previousGuardTargetId"]).isEqualTo("u4")
    }

    @Test
    fun `getGameState - GUARD gets null previousGuardTargetId on first night`() {
        val guard = player("u1", 1, PlayerRole.GUARD)
        val players = listOf(guard)
        val users = listOf(user("u1", "Guard"))
        val game = game()
        setupGameAndPlayers(game, players, users, NightSubPhase.GUARD_PICK)

        val np = nightPhase(subPhase = NightSubPhase.GUARD_PICK)  // no prevGuardTarget
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        val result = gameService.getGameState(gameId, "u1")
        val night = nightPhaseResult(result)

        assertThat(night["previousGuardTargetId"]).isNull()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ROLE_REVEAL phase
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getGameState - ROLE_REVEAL returns confirmedCount and totalCount`() {
        val p1 = player("u1", 1, PlayerRole.VILLAGER).also { it.confirmedRole = true }
        val p2 = player("u2", 2, PlayerRole.WEREWOLF)
        val p3 = player("u3", 3, PlayerRole.SEER)
        val players = listOf(p1, p2, p3)
        val users = listOf(user("u1", "Alice"), user("u2", "Bob"), user("u3", "Carol"))
        val game = game(phase = GamePhase.ROLE_REVEAL).also { it.dayNumber = 1 }
        setupGameAndPlayers(game, players, users)

        val result = gameService.getGameState(gameId, "u1")

        @Suppress("UNCHECKED_CAST")
        val roleReveal = result["roleReveal"] as Map<String, Any?>
        assertThat(roleReveal["confirmedCount"]).isEqualTo(1)
        assertThat(roleReveal["totalCount"]).isEqualTo(3)
        assertThat(roleReveal["confirmedUserIds"]).isEqualTo(listOf("u1"))
    }

    @Test
    fun `getGameState - ROLE_REVEAL werewolf sees teammate list`() {
        val wolf1 = player("u1", 1, PlayerRole.WEREWOLF)
        val wolf2 = player("u2", 2, PlayerRole.WEREWOLF)
        val villager = player("u3", 3, PlayerRole.VILLAGER)
        val players = listOf(wolf1, wolf2, villager)
        val users = listOf(user("u1", "Wolf1"), user("u2", "Wolf2"), user("u3", "Dave"))
        val game = game(phase = GamePhase.ROLE_REVEAL).also { it.dayNumber = 1 }
        setupGameAndPlayers(game, players, users)

        val result = gameService.getGameState(gameId, "u1")

        @Suppress("UNCHECKED_CAST")
        val roleReveal = result["roleReveal"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val teammates = roleReveal["teammates"] as List<String>
        assertThat(teammates).containsExactly("Wolf2")
    }

    @Test
    fun `getGameState - ROLE_REVEAL WHITE_WOLF_KING sees WEREWOLF teammates`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val king = player("u2", 2, PlayerRole.WHITE_WOLF_KING)
        val villager = player("u3", 3, PlayerRole.VILLAGER)
        val users = listOf(user("u1", "Wolf1"), user("u2", "King"), user("u3", "Dave"))
        val game = game(phase = GamePhase.ROLE_REVEAL).also { it.dayNumber = 1 }
        setupGameAndPlayers(game, listOf(wolf, king, villager), users)

        @Suppress("UNCHECKED_CAST")
        val roleReveal = gameService.getGameState(gameId, "u2")["roleReveal"] as Map<String, Any?>
        assertThat(roleReveal["teammates"] as List<*>).containsExactly("Wolf1")
    }

    @Test
    fun `getGameState - ROLE_REVEAL non-werewolf gets empty teammates`() {
        val villager = player("u1", 1, PlayerRole.VILLAGER)
        val wolf = player("u2", 2, PlayerRole.WEREWOLF)
        val players = listOf(villager, wolf)
        val users = listOf(user("u1", "Alice"), user("u2", "Bob"))
        val game = game(phase = GamePhase.ROLE_REVEAL).also { it.dayNumber = 1 }
        setupGameAndPlayers(game, players, users)

        val result = gameService.getGameState(gameId, "u1")

        @Suppress("UNCHECKED_CAST")
        val roleReveal = result["roleReveal"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val teammates = roleReveal["teammates"] as List<String>
        assertThat(teammates).isEmpty()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GAME_OVER phase — roles exposed for all players
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getGameState - GAME_OVER exposes roles for all players`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val seer = player("u2", 2, PlayerRole.SEER)
        val villager = player("u3", 3, PlayerRole.VILLAGER)
        val players = listOf(wolf, seer, villager)
        val users = listOf(user("u1", "Wolf"), user("u2", "Seer"), user("u3", "Villager"))
        val game = game(phase = GamePhase.GAME_OVER).also {
            it.dayNumber = 1
            it.winner = WinnerSide.WEREWOLF
        }
        setupGameAndPlayers(game, players, users)

        // Requesting as u3 (villager) — should still see everyone's roles
        val result = gameService.getGameState(gameId, "u3")

        @Suppress("UNCHECKED_CAST")
        val playerList = result["players"] as List<Map<String, Any?>>
        val wolfEntry = playerList.first { it["userId"] == "u1" }
        val seerEntry = playerList.first { it["userId"] == "u2" }
        assertThat(wolfEntry["role"]).isEqualTo("WEREWOLF")
        assertThat(seerEntry["role"]).isEqualTo("SEER")
    }

    @Test
    fun `getGameState - during NIGHT phase other player roles are hidden`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val seer = player("u2", 2, PlayerRole.SEER)
        val players = listOf(wolf, seer)
        val users = listOf(user("u1", "Wolf"), user("u2", "Seer"))
        val game = game(phase = GamePhase.NIGHT)
        setupGameAndPlayers(game, players, users, NightSubPhase.WAITING)

        val np = nightPhase(subPhase = NightSubPhase.WAITING)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day)).thenReturn(Optional.of(np))

        // Requesting as u2 (seer) — should only see own role
        val result = gameService.getGameState(gameId, "u2")

        @Suppress("UNCHECKED_CAST")
        val playerList = result["players"] as List<Map<String, Any?>>
        val wolfEntry = playerList.first { it["userId"] == "u1" }
        val seerEntry = playerList.first { it["userId"] == "u2" }
        assertThat(wolfEntry["role"]).isNull() // hidden
        assertThat(seerEntry["role"]).isEqualTo("SEER") // own role visible
    }


    // ── Mechanical wolf is outside the pack ───────────────────────────────────

    private fun wolfPickWith(vararg players: GamePlayer, wolfTarget: String? = null) {
        setupGameAndPlayers(game(), players.toList(), players.map { user(it.userId, "N${it.seatIndex}") }, NightSubPhase.WEREWOLF_PICK)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.of(nightPhase(NightSubPhase.WEREWOLF_PICK, wolfTarget = wolfTarget)))
    }

    @Test
    fun `mechanical wolf gets no teammates and no selectedTargetId during WEREWOLF_PICK`() {
        wolfPickWith(
            player("u1", 1, PlayerRole.WEREWOLF), player("u2", 2, PlayerRole.MECHANICAL_WOLF),
            player("u3", 3, PlayerRole.VILLAGER), wolfTarget = "u3",
        )

        val night = nightPhaseResult(gameService.getGameState(gameId, "u2"))

        assertThat(night).doesNotContainKeys("teammates", "selectedTargetId")
        assertThat(gameService.getGameState(gameId, "u2")["wolfTeammates"] as List<*>).isEmpty()
    }

    @Test
    fun `pack wolf teammates exclude the mechanical wolf`() {
        wolfPickWith(
            player("u1", 1, PlayerRole.WEREWOLF), player("u2", 2, PlayerRole.MECHANICAL_WOLF),
            player("u3", 3, PlayerRole.WHITE_WOLF_KING), player("u4", 4, PlayerRole.VILLAGER),
        )

        val state = gameService.getGameState(gameId, "u1")

        assertThat(nightPhaseResult(state)["teammates"] as List<String>).containsExactly("3·N3")
        assertThat(state["wolfTeammates"] as List<String>).containsExactly("u3")
    }

    @Test
    fun `wolfKiller is true only for killers`() {
        wolfPickWith(
            player("u1", 1, PlayerRole.WEREWOLF), player("u2", 2, PlayerRole.MECHANICAL_WOLF),
            player("u3", 3, PlayerRole.VILLAGER),
        )
        fun killer(uid: String) = nightPhaseResult(gameService.getGameState(gameId, uid))["wolfKiller"]
        assertThat(killer("u1")).isEqualTo(true)
        assertThat(killer("u2")).isEqualTo(false)
        assertThat(killer("u3")).isEqualTo(false)
    }

    @Test
    fun `wolfKiller is true for the mechanical wolf once the pack is dead`() {
        wolfPickWith(
            player("u1", 1, PlayerRole.WEREWOLF).also { it.alive = false }, player("u2", 2, PlayerRole.MECHANICAL_WOLF),
            player("u3", 3, PlayerRole.VILLAGER), wolfTarget = "u3",
        )

        val night = nightPhaseResult(gameService.getGameState(gameId, "u2"))

        assertThat(night["wolfKiller"]).isEqualTo(true)
        assertThat(night["selectedTargetId"]).isEqualTo("u3")
    }

    @Test
    fun `a dead pack wolf gets neither selectedTargetId nor wolfKiller`() {
        wolfPickWith(
            player("u1", 1, PlayerRole.WEREWOLF).also { it.alive = false }, player("u2", 2, PlayerRole.WEREWOLF),
            player("u3", 3, PlayerRole.VILLAGER), wolfTarget = "u3",
        )

        val dead = nightPhaseResult(gameService.getGameState(gameId, "u1"))
        val alive = nightPhaseResult(gameService.getGameState(gameId, "u2"))

        assertThat(dead["wolfKiller"]).isEqualTo(false)
        assertThat(dead).doesNotContainKey("selectedTargetId")
        assertThat(alive["wolfKiller"]).isEqualTo(true)
        assertThat(alive["selectedTargetId"]).isEqualTo("u3")
    }

    // ── 狼美人: pack wolves learn who it is (G1) ──────────────────────────────

    private val beautyUsers = listOf(
        user("u1", "Wolf"), user("u2", "Beauty"), user("u3", "Seer"), user("u4", "Garg"), user("u5", "Mech"), user("u6", "Vil"),
    )
    private val beautyBoard = listOf(
        player("u1", 1, PlayerRole.WEREWOLF), player("u2", 2, PlayerRole.WOLF_BEAUTY), player("u3", 3, PlayerRole.SEER),
        player("u4", 4, PlayerRole.GARGOYLE), player("u5", 5, PlayerRole.MECHANICAL_WOLF), player("u6", 6, PlayerRole.VILLAGER),
    )

    @Test
    fun `G1 - pack wolves see the wolf beauty in night state, everyone else gets no key`() {
        setupGameAndPlayers(game(), beautyBoard, beautyUsers)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.of(nightPhase(subPhase = NightSubPhase.WEREWOLF_PICK)))

        for (pack in listOf("u1", "u2"))
            assertThat(nightPhaseResult(gameService.getGameState(gameId, pack))).`as`(pack).containsEntry("wolfBeautyUserId", "u2")
        for (other in listOf("u3", "u4", "u5", "u6"))
            assertThat(nightPhaseResult(gameService.getGameState(gameId, other))).`as`(other).doesNotContainKey("wolfBeautyUserId")
    }

    @Test
    fun `G1 - a pack wolf in a game without a wolf beauty gets null`() {
        setupGameAndPlayers(game(), beautyBoard.filter { it.userId != "u2" }, beautyUsers)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, day))
            .thenReturn(Optional.of(nightPhase(subPhase = NightSubPhase.WEREWOLF_PICK)))

        val night = nightPhaseResult(gameService.getGameState(gameId, "u1"))
        assertThat(night).containsKey("wolfBeautyUserId")
        assertThat(night["wolfBeautyUserId"]).isNull()
    }

    @Test
    fun `G1 - roleReveal carries wolfBeautyUserId for pack wolves only`() {
        setupGameAndPlayers(game(phase = GamePhase.ROLE_REVEAL).also { it.dayNumber = 1 }, beautyBoard, beautyUsers)

        @Suppress("UNCHECKED_CAST")
        fun reveal(viewer: String) = gameService.getGameState(gameId, viewer)["roleReveal"] as Map<String, Any?>
        assertThat(reveal("u1")).containsEntry("wolfBeautyUserId", "u2")
        assertThat(reveal("u2")).containsEntry("wolfBeautyUserId", "u2")
        for (other in listOf("u3", "u4", "u5", "u6"))
            assertThat(reveal(other)).`as`(other).doesNotContainKey("wolfBeautyUserId")
    }
}
