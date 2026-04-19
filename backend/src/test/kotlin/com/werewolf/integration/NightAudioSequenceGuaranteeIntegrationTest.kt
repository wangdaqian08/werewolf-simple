package com.werewolf.integration

import com.werewolf.game.DomainEvent
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_ROOM_CODE
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.JOIN_ROOM_URL
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

/**
 * End-to-end guarantee that when the game transitions from DAY to NIGHT and the
 * wolf phase begins, the player hears exactly this ordered audio sequence:
 *
 *     goes_dark_close_eyes.mp3  →  wolf_howl.mp3  →  wolf_open_eyes.mp3
 *
 * The decoupled architecture delivers these via TWO backend broadcasts:
 *
 *   • A high-priority phase-transition broadcast carrying the night ambience
 *     (goes_dark_close_eyes + wolf_howl).
 *   • A lower-priority role-loop broadcast for the first role's open-eyes
 *     (wolf_open_eyes), owned by WerewolfAudioConfig.
 *
 * Unit tests on the frontend composable prove the client's queue logic. Unit
 * tests on AudioService prove each broadcast's file list. This test bridges
 * both sides by spying on StompPublisher while the real night pipeline runs —
 * if any link in the chain breaks (phase-transition files change, role loop
 * stops broadcasting, priority values flip), this test fails.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NightAudioSequenceGuaranteeIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var nightPhaseRepository: NightPhaseRepository
    @Autowired lateinit var nightOrchestrator: NightOrchestrator
    @Autowired lateinit var timing: com.werewolf.config.GameTimingProperties

    @SpyBean lateinit var stompPublisher: StompPublisher

    companion object {
        private const val START_URL = "/api/game/start"
        private const val SEAT_URL = "/api/room/seat"
        private const val READY_URL = "/api/room/ready"
        private const val ACTION_URL = "/api/game/action"
    }

    private fun login(nickname: String): Pair<String, String> {
        @Suppress("UNCHECKED_CAST")
        val body = restTemplate.postForEntity(LOGIN_URL, mapOf("nickname" to nickname), Map::class.java).body!!
        return (body[FIELD_TOKEN] as String) to ((body["user"] as Map<*, *>)["userId"] as String)
    }

    private fun headers(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    private fun action(token: String, gameId: Int, actionType: String) =
        restTemplate.postForEntity(
            ACTION_URL,
            HttpEntity(mapOf("gameId" to gameId, "actionType" to actionType), headers(token)),
            Map::class.java,
        )

    @Test
    fun `DAY to WEREWOLF_PICK broadcasts goes_dark_close_eyes, wolf_howl, then wolf_open_eyes in order`() {
        // ── Lobby setup: 4 players, no sheriff, no special roles ──
        val (hostToken, hostId) = login("NAG1H")
        val guests = (1..3).map { login("NAG1G$it") }

        @Suppress("UNCHECKED_CAST")
        val roomBody = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(FIELD_CONFIG to mapOf(
                    FIELD_TOTAL_PLAYERS to 4,
                    "roles" to emptyList<String>(),
                    "hasSheriff" to false,
                )),
                headers(hostToken),
            ),
            Map::class.java,
        ).body!! as Map<String, Any?>

        val roomId = (roomBody[FIELD_ROOM_ID] as String).toInt()
        val roomCode = roomBody[FIELD_ROOM_CODE] as String

        restTemplate.postForEntity(
            SEAT_URL,
            HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), headers(hostToken)),
            Map::class.java,
        )
        guests.forEachIndexed { idx, (guestToken, _) ->
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), headers(guestToken)), Map::class.java)
            restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to idx + 1, "roomId" to roomId), headers(guestToken)), Map::class.java)
            restTemplate.postForEntity(READY_URL, HttpEntity(mapOf("ready" to true, "roomId" to roomId), headers(guestToken)), Map::class.java)
        }

        // ── Start game, confirm roles, start night ──
        val tokensByUserId = buildMap<String, String> {
            put(hostId, hostToken)
            guests.forEach { (t, id) -> put(id, t) }
        }

        restTemplate.postForEntity(START_URL, HttpEntity(mapOf("roomId" to roomId), headers(hostToken)), Map::class.java)
        val game = gameRepository.findAll().first { it.roomId == roomId }
        val gameId = game.gameId!!

        tokensByUserId.values.forEach { token ->
            assertThat(action(token, gameId, "CONFIRM_ROLE").statusCode).isEqualTo(HttpStatus.OK)
        }

        // ── Trigger DAY → NIGHT ──
        assertThat(action(hostToken, gameId, "START_NIGHT").statusCode).isEqualTo(HttpStatus.OK)

        // ── Wait for the role loop to reach WEREWOLF_PICK deferred-await ──
        // At that point both broadcasts (phase transition + first role open-eyes) have
        // been published via stompPublisher.broadcastGame. The loop itself waits for a
        // wolf-kill action, so it will not progress past WEREWOLF_PICK — which means
        // no subsequent role's open-eyes broadcast can contaminate our capture.
        waitForNightSubPhaseReady(gameId, 1, NightSubPhase.WEREWOLF_PICK)

        // ── Capture every broadcastGame call for this game ──
        val eventCaptor = argumentCaptor<Any>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), eventCaptor.capture())

        // Extract only AudioSequence events in broadcast order.
        val audioSequences = eventCaptor.allValues
            .filterIsInstance<DomainEvent.AudioSequence>()
            .map { it.audioSequence }

        // Flatten into the ordered list of files the client will ultimately play.
        // The frontend audio queue plays them back in this exact order provided
        // that low-priority sequences append (see useAudioService.ts) — a property
        // verified independently by the frontend unit tests.
        val orderedFiles = audioSequences.flatMap { it.audioFiles }

        // ── The three guaranteed audios, in order ──
        assertThat(orderedFiles).containsSequence(
            "goes_dark_close_eyes.mp3",
            "wolf_howl.mp3",
            "wolf_open_eyes.mp3",
        )

        // ── Priority contract: phase transition is high priority, role audio is low ──
        // Swapping these would break the client-side queue-append guarantee.
        val phaseTransition = audioSequences.first { "wolf_howl.mp3" in it.audioFiles }
        val wolfOpenEyes = audioSequences.first { it.audioFiles == listOf("wolf_open_eyes.mp3") }
        assertThat(phaseTransition.priority).isEqualTo(10)
        assertThat(wolfOpenEyes.priority).isLessThan(10)
    }

    @Test
    fun `full night with wolf-seer-witch-guard broadcasts every role's open and close audio in order`() {
        // This test locks in the complete decoupled audio flow for a full-roles night.
        // Each role owns its own open-eyes and close-eyes files. The role loop drives
        // them in sequence. The player hears, in order:
        //
        //   goes_dark_close_eyes.mp3   (phase transition)
        //   wolf_howl.mp3              (phase transition)
        //   wolf_open_eyes.mp3         (werewolf role start)
        //   wolf_close_eyes.mp3        (werewolf role end)
        //   seer_open_eyes.mp3         (seer role start)
        //   seer_close_eyes.mp3        (seer role end)
        //   witch_open_eyes.mp3        (witch role start)
        //   witch_close_eyes.mp3       (witch role end)
        //   guard_open_eyes.mp3        (guard role start)
        //   guard_close_eyes.mp3       (guard role end)
        //
        // If we ever re-couple role audio into phase-transition or sub-phase-transition
        // sequences, or a role stops emitting open/close files, this test fails.
        val setup = setupFullRolesGame(prefix = "FAG")
        val gameId = setup.gameId

        assertThat(action(setup.hostToken, gameId, "START_NIGHT").statusCode).isEqualTo(HttpStatus.OK)

        // Drive each role's action as the loop reaches its deferred-await so the
        // loop can progress through every role within the test's @Timeout window.
        waitForNightSubPhaseReady(gameId, 1, NightSubPhase.WEREWOLF_PICK)
        val wolfToken = findRoleToken(gameId, setup.tokensByUserId, PlayerRole.WEREWOLF)
        val firstVillager = gamePlayerRepository.findByGameId(gameId)
            .first { it.role != PlayerRole.WEREWOLF && it.role != PlayerRole.SEER && it.role != PlayerRole.WITCH && it.role != PlayerRole.GUARD }
        assertThat(submitWith(wolfToken, gameId, "WOLF_KILL", firstVillager.userId).statusCode).isEqualTo(HttpStatus.OK)

        waitForNightSubPhaseReady(gameId, 1, NightSubPhase.SEER_PICK)
        val seerToken = findRoleToken(gameId, setup.tokensByUserId, PlayerRole.SEER)
        assertThat(submitWith(seerToken, gameId, "SEER_CHECK", firstVillager.userId).statusCode).isEqualTo(HttpStatus.OK)

        waitForNightSubPhaseReady(gameId, 1, NightSubPhase.SEER_RESULT)
        assertThat(action(seerToken, gameId, "SEER_CONFIRM").statusCode).isEqualTo(HttpStatus.OK)

        waitForNightSubPhaseReady(gameId, 1, NightSubPhase.WITCH_ACT)
        val witchToken = findRoleToken(gameId, setup.tokensByUserId, PlayerRole.WITCH)
        assertThat(submitWithPayload(witchToken, gameId, "WITCH_ACT", mapOf("useAntidote" to false)).statusCode).isEqualTo(HttpStatus.OK)

        waitForNightSubPhaseReady(gameId, 1, NightSubPhase.GUARD_PICK)
        val guardToken = findRoleToken(gameId, setup.tokensByUserId, PlayerRole.GUARD)
        assertThat(action(guardToken, gameId, "GUARD_SKIP").statusCode).isEqualTo(HttpStatus.OK)

        // Wait for the last role's close-eyes broadcast and night resolution.
        Thread.sleep(8_000)

        val orderedFiles = capturedAudioFilesInOrder(gameId)

        assertThat(orderedFiles).containsSequence(
            "goes_dark_close_eyes.mp3",
            "wolf_howl.mp3",
            "wolf_open_eyes.mp3",
            "wolf_close_eyes.mp3",
            "seer_open_eyes.mp3",
            "seer_close_eyes.mp3",
            "witch_open_eyes.mp3",
            "witch_close_eyes.mp3",
            "guard_open_eyes.mp3",
            "guard_close_eyes.mp3",
        )
    }

    @Test
    fun `inter-role audio gap - at least 3 seconds elapse between close-eyes and next role's open-eyes`() {
        // The role loop sleeps config.audioCooldownMs + config.interRoleGapMs between
        // one role's close-eyes broadcast and the next role's open-eyes broadcast.
        // With defaults (cooldown 2000ms, gap 3000ms), the observed wall-clock gap
        // should be ≥ 3 seconds. This guarantees the close-eyes sound fully drains
        // on the client before the next role's open-eyes sound begins — no overlap,
        // no perceived cross-fade at the role boundary.
        val setup = setupFullRolesGame(prefix = "GAP")
        val gameId = setup.gameId

        assertThat(action(setup.hostToken, gameId, "START_NIGHT").statusCode).isEqualTo(HttpStatus.OK)

        // Drive wolves + seer to force at least one wolf_close → seer_open transition.
        waitForNightSubPhaseReady(gameId, 1, NightSubPhase.WEREWOLF_PICK)
        val wolfToken = findRoleToken(gameId, setup.tokensByUserId, PlayerRole.WEREWOLF)
        val victim = gamePlayerRepository.findByGameId(gameId)
            .first { it.role != PlayerRole.WEREWOLF && it.role != PlayerRole.SEER && it.role != PlayerRole.WITCH && it.role != PlayerRole.GUARD }
        assertThat(submitWith(wolfToken, gameId, "WOLF_KILL", victim.userId).statusCode).isEqualTo(HttpStatus.OK)

        waitForNightSubPhaseReady(gameId, 1, NightSubPhase.SEER_PICK)
        // SEER_PICK reached means: wolf_close_eyes has already been broadcast AND
        // seer_open_eyes has been broadcast right before the deferred-await. Both
        // broadcasts are already in the spy.

        val timestamps = capturedAudioBroadcastsInOrder(gameId)
        val wolfClose = timestamps.first { it.first == "wolf_close_eyes.mp3" }.second
        val seerOpen = timestamps.first { it.first == "seer_open_eyes.mp3" }.second

        val gapMs = seerOpen - wolfClose
        // Expected minimum gap = configured interRoleGapMs (the pause after one role's
        // close-eyes cooldown and before the next role's open-eyes). Reading it from
        // properties makes the test work for both prod defaults (3s) and the test
        // profile's shrunk values, while still failing if the gap regresses to zero.
        val expectedGapFloorMs = timing.interRoleGapMs ?: 3_000L
        assertThat(gapMs)
            .describedAs("Gap between wolf_close_eyes and seer_open_eyes should be ≥ ${expectedGapFloorMs}ms, was ${gapMs}ms")
            .isGreaterThanOrEqualTo(expectedGapFloorMs)

        // Resolve the night so the test cleans up cleanly.
        val seerToken = findRoleToken(gameId, setup.tokensByUserId, PlayerRole.SEER)
        action(seerToken, gameId, "SEER_CONFIRM") // best-effort; ignore rejection if subphase moved
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private data class GameSetup(
        val hostToken: String,
        val roomId: Int,
        val gameId: Int,
        val tokensByUserId: Map<String, String>,
    )

    /** Create a 6-player game with seer + witch + guard enabled so the role loop
     *  exercises every role-owned audio broadcast. No sheriff (avoids election). */
    private fun setupFullRolesGame(prefix: String): GameSetup {
        val (hostToken, hostId) = login("${prefix}H")
        val guests = (1..5).map { login("${prefix}G$it") }

        @Suppress("UNCHECKED_CAST")
        val roomBody = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(FIELD_CONFIG to mapOf(
                    FIELD_TOTAL_PLAYERS to 6,
                    "roles" to listOf("SEER", "WITCH", "GUARD"),
                    "hasSheriff" to false,
                )),
                headers(hostToken),
            ),
            Map::class.java,
        ).body!! as Map<String, Any?>

        val roomId = (roomBody[FIELD_ROOM_ID] as String).toInt()
        val roomCode = roomBody[FIELD_ROOM_CODE] as String

        restTemplate.postForEntity(
            SEAT_URL,
            HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), headers(hostToken)),
            Map::class.java,
        )
        guests.forEachIndexed { idx, (guestToken, _) ->
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), headers(guestToken)), Map::class.java)
            restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to idx + 1, "roomId" to roomId), headers(guestToken)), Map::class.java)
            restTemplate.postForEntity(READY_URL, HttpEntity(mapOf("ready" to true, "roomId" to roomId), headers(guestToken)), Map::class.java)
        }

        val tokensByUserId = buildMap<String, String> {
            put(hostId, hostToken)
            guests.forEach { (t, id) -> put(id, t) }
        }

        restTemplate.postForEntity(START_URL, HttpEntity(mapOf("roomId" to roomId), headers(hostToken)), Map::class.java)
        val game = gameRepository.findAll().first { it.roomId == roomId }
        val gameId = game.gameId!!

        tokensByUserId.values.forEach { token ->
            assertThat(action(token, gameId, "CONFIRM_ROLE").statusCode).isEqualTo(HttpStatus.OK)
        }

        return GameSetup(hostToken, roomId, gameId, tokensByUserId)
    }

    private fun findRoleToken(gameId: Int, tokensByUserId: Map<String, String>, role: PlayerRole): String {
        val userId = gamePlayerRepository.findByGameId(gameId).first { it.role == role }.userId
        return tokensByUserId.getValue(userId)
    }

    private fun submitWith(token: String, gameId: Int, actionType: String, targetUserId: String) =
        restTemplate.postForEntity(
            ACTION_URL,
            HttpEntity(
                mapOf("gameId" to gameId, "actionType" to actionType, "targetUserId" to targetUserId),
                headers(token),
            ),
            Map::class.java,
        )

    private fun submitWithPayload(token: String, gameId: Int, actionType: String, payload: Map<String, Any?>) =
        restTemplate.postForEntity(
            ACTION_URL,
            HttpEntity(
                mapOf("gameId" to gameId, "actionType" to actionType, "payload" to payload),
                headers(token),
            ),
            Map::class.java,
        )

    /** Flatten captured AudioSequence events into their ordered audio-file list. */
    private fun capturedAudioFilesInOrder(gameId: Int): List<String> =
        capturedAudioBroadcastsInOrder(gameId).map { it.first }

    /** Each broadcasted audio file with its broadcast timestamp, in broadcast order. */
    private fun capturedAudioBroadcastsInOrder(gameId: Int): List<Pair<String, Long>> {
        val eventCaptor = argumentCaptor<Any>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), eventCaptor.capture())
        return eventCaptor.allValues
            .filterIsInstance<DomainEvent.AudioSequence>()
            .flatMap { event ->
                val ts = event.audioSequence.timestamp
                event.audioSequence.audioFiles.map { it to ts }
            }
    }

    /** Same helper as NightPhaseDeadRoleIntegrationTest — see rationale there. */
    private fun waitForNightSubPhaseReady(
        gameId: Int,
        dayNumber: Int,
        targetSubPhase: NightSubPhase,
        timeoutMs: Long = 20_000L,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val np = nightPhaseRepository.findByGameIdAndDayNumber(gameId, dayNumber).orElse(null)
            if (np?.subPhase == targetSubPhase) {
                Thread.sleep(500)
                return
            }
            Thread.sleep(250)
        }
        error("Night role loop did not reach $targetSubPhase on day $dayNumber within ${timeoutMs}ms")
    }
}
