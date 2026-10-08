package com.werewolf.integration

import com.werewolf.controller.AudioTrackDto
import com.werewolf.dto.ActionLogEntryDto
import com.werewolf.dto.AuthResponse
import com.werewolf.dto.CreateRoomRequest
import com.werewolf.dto.GameActionRequestDto
import com.werewolf.dto.GoogleProvider
import com.werewolf.dto.LeaveRoomRequest
import com.werewolf.dto.PerkActivationDto
import com.werewolf.dto.ProvidersResponse
import com.werewolf.dto.RoomConfigDto
import com.werewolf.dto.RoomConfigRequest
import com.werewolf.dto.RoomDto
import com.werewolf.dto.RoomPlayerDto
import com.werewolf.dto.SetReadyRequest
import com.werewolf.game.DomainEvent
import com.werewolf.model.ActionType
import com.werewolf.model.AudioSequence
import com.werewolf.model.GamePhase
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.messaging.MessageHeaders
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.mock.http.MockHttpOutputMessage
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
import kotlin.reflect.KClass

/**
 * Pins the JSON contract with the frontend, through the same converters Spring
 * uses at runtime: STOMP events via the broker template, REST bodies via the
 * MVC JSON converter. Guards framework/Jackson upgrades against silent wire
 * changes (renamed `is*` booleans, dropped nulls, stricter request parsing).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class JsonWireFormatTest {

    @Autowired lateinit var messagingTemplate: SimpMessagingTemplate
    @Autowired lateinit var handlerAdapter: RequestMappingHandlerAdapter

    private fun stompJson(event: DomainEvent): String {
        val message = messagingTemplate.messageConverter.toMessage(event, MessageHeaders(emptyMap()))!!
        return String(message.payload as ByteArray, Charsets.UTF_8)
    }

    @Suppress("UNCHECKED_CAST")
    private val jsonConverter: HttpMessageConverter<Any> by lazy {
        handlerAdapter.messageConverters
            .first { it.canWrite(Map::class.java, MediaType.APPLICATION_JSON) } as HttpMessageConverter<Any>
    }

    private fun responseJson(body: Any): String {
        val output = MockHttpOutputMessage()
        jsonConverter.write(body, MediaType.APPLICATION_JSON, output)
        return output.bodyAsString
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> readRequest(json: String, type: KClass<T>): T {
        val input = MockHttpInputMessage(json.toByteArray()).apply { headers.contentType = MediaType.APPLICATION_JSON }
        return jsonConverter.read(type.java, input) as T
    }

    private fun assertJson(cases: List<Pair<Any, String>>, render: (Any) -> String) =
        assertAll(cases.map { (value, expected) ->
            Executable { JSONAssert.assertEquals("${value::class.simpleName}", expected, render(value), JSONCompareMode.STRICT) }
        })

    private val events: List<Pair<DomainEvent, String>> = listOf(
        DomainEvent.PhaseChanged(1, GamePhase.DAY_VOTING, "VOTING") to
                """{"type":"PhaseChanged","gameId":1,"phase":"DAY_VOTING","subPhase":"VOTING"}""",
        DomainEvent.NightSubPhaseChanged(1, NightSubPhase.WITCH_ACT) to
                """{"type":"NightSubPhaseChanged","gameId":1,"subPhase":"WITCH_ACT"}""",
        DomainEvent.RoleAssigned(1, "u1", PlayerRole.SEER) to
                """{"type":"RoleAssigned","gameId":1,"userId":"u1","role":"SEER"}""",
        DomainEvent.NightResult(1, listOf("u2", "u3")) to
                """{"type":"NightResult","gameId":1,"kills":["u2","u3"]}""",
        DomainEvent.SeerResult(1, "u2", true) to
                """{"type":"SeerResult","gameId":1,"checkedUserId":"u2","isWerewolf":true}""",
        DomainEvent.VoteSubmitted(1, "u1") to
                """{"type":"VoteSubmitted","gameId":1,"voterUserId":"u1"}""",
        DomainEvent.VoteTally(1, null, mapOf("u2" to 1.5, "u3" to 2.0)) to
                """{"type":"VoteTally","gameId":1,"eliminatedUserId":null,"tally":{"u2":1.5,"u3":2.0}}""",
        DomainEvent.PlayerEliminated(1, "u2", PlayerRole.WEREWOLF) to
                """{"type":"PlayerEliminated","gameId":1,"userId":"u2","role":"WEREWOLF"}""",
        DomainEvent.HunterShot(1, "u4", "u5") to
                """{"type":"HunterShot","gameId":1,"hunterUserId":"u4","targetUserId":"u5"}""",
        DomainEvent.BadgeHandover(1, "u1", null) to
                """{"type":"BadgeHandover","gameId":1,"fromUserId":"u1","toUserId":null}""",
        DomainEvent.SheriffElected(1, "u1") to
                """{"type":"SheriffElected","gameId":1,"sheriffUserId":"u1"}""",
        DomainEvent.GameOver(1, WinnerSide.VILLAGER) to
                """{"type":"GameOver","gameId":1,"winner":"VILLAGER"}""",
        DomainEvent.GameSettled(1, mapOf("u1" to 20, "u2" to 5)) to
                """{"type":"GameSettled","gameId":1,"rewards":{"u1":20,"u2":5}}""",
        DomainEvent.RoleConfirmed(1, "u1") to
                """{"type":"RoleConfirmed","gameId":1,"userId":"u1"}""",
        DomainEvent.IdiotRevealed(1, "u6") to
                """{"type":"IdiotRevealed","gameId":1,"userId":"u6"}""",
        DomainEvent.WolfSelectionChanged(1, "u3") to
                """{"type":"WolfSelectionChanged","gameId":1,"selectedTargetUserId":"u3"}""",
        DomainEvent.AudioSequence(
            1, AudioSequence("seq-1", GamePhase.NIGHT, null, listOf("wolf_open.mp3"), 2, 1_700_000_000_000L),
        ) to """{"type":"AudioSequence","gameId":1,"audioSequence":{"id":"seq-1","phase":"NIGHT","subPhase":null,""" +
                """"audioFiles":["wolf_open.mp3"],"priority":2,"timestamp":1700000000000}}""",
        DomainEvent.OpenEyes(1, PlayerRole.WITCH, GamePhase.NIGHT, 2) to
                """{"type":"OpenEyes","gameId":1,"role":"WITCH","phase":"NIGHT","nightNumber":2}""",
        DomainEvent.CloseEyes(1, PlayerRole.WITCH, GamePhase.NIGHT, 2) to
                """{"type":"CloseEyes","gameId":1,"role":"WITCH","phase":"NIGHT","nightNumber":2}""",
        DomainEvent.WolfSelfDestructed(1, "u3", 4, "Wolfie") to
                """{"type":"WolfSelfDestructed","gameId":1,"userId":"u3","seatIndex":4,"nickname":"Wolfie"}""",
        DomainEvent.RoleAction(1, "u7", PlayerRole.WITCH, "WITCH_ACT", listOf("u2"), canHeal = true, timeoutMs = 30_000L) to
                """{"type":"RoleAction","gameId":1,"userId":"u7","role":"WITCH","actionType":"WITCH_ACT",""" +
                """"targets":["u2"],"canHeal":true,"canPoison":null,"timeoutMs":30000}""",
        DomainEvent.TimerUpdated(1, 12_000L, 60_000L, true) to
                """{"type":"TimerUpdated","gameId":1,"remainingMs":12000,"durationMs":60000,"running":true}""",
    )

    @Test
    fun `STOMP events keep their JSON shape`() = assertJson(events) { stompJson(it as DomainEvent) }

    @Test
    fun `every DomainEvent subtype has a pinned shape`() {
        assertThat(events.map { it.first::class }).containsExactlyInAnyOrderElementsOf(DomainEvent::class.sealedSubclasses)
    }

    @Test
    fun `REST response bodies keep their JSON shape`() = assertJson(
        listOf(
            AuthResponse("jwt-token", AuthResponse.UserDto("u1", "Alice", null)) to
                    """{"token":"jwt-token","user":{"userId":"u1","nickname":"Alice","avatarUrl":null}}""",
            ProvidersResponse(GoogleProvider("google-client"), null, true) to
                    """{"google":{"clientId":"google-client"},"wechat":null,"guest":true}""",
            RoomDto(
                roomId = "10", roomCode = "1234", hostId = "u1", status = "WAITING",
                players = listOf(RoomPlayerDto("u1", "Alice", null, 0, "READY", true)),
                config = RoomConfigDto(9, 3, listOf(PlayerRole.SEER, PlayerRole.WITCH)),
                perkActivations = listOf(PerkActivationDto("u1", "NIGHT1_IMMUNITY", "Night shield")),
            ) to """{"roomId":"10","roomCode":"1234","hostId":"u1","status":"WAITING",""" +
                    """"players":[{"userId":"u1","nickname":"Alice","avatar":null,"seatIndex":0,"status":"READY","isHost":true}],""" +
                    """"config":{"totalPlayers":9,"wolfCount":3,"roles":["SEER","WITCH"],"hasSheriff":true,""" +
                    """"winCondition":"CLASSIC","bgmTrack":null,"witchSelfSaveAllowed":true,"perksAllowed":true},""" +
                    """"activeGameId":null,"perkActivations":[{"userId":"u1","perkCode":"NIGHT1_IMMUNITY","perkName":"Night shield"}]}""",
            ActionLogEntryDto(5, "NIGHT_DEATH", """{"dayNumber":1}""", "u2", null) to
                    """{"id":5,"eventType":"NIGHT_DEATH","message":"{\"dayNumber\":1}","targetUserId":"u2","createdAt":null}""",
            listOf(AudioTrackDto(null, null, "无 (None)"), AudioTrackDto("a.mp3", "a.mp3", "A")) to
                    """[{"id":null,"filename":null,"displayName":"无 (None)"},{"id":"a.mp3","filename":"a.mp3","displayName":"A"}]""",
            mapOf("votes" to 1.5, "weight" to 2.0, "count" to 3, "deadline" to 1_700_000_000_000L, "open" to true, "none" to null) to
                    """{"votes":1.5,"weight":2.0,"count":3,"deadline":1700000000000,"open":true,"none":null}""",
        ),
        ::responseJson,
    )

    @Test
    fun `game action request accepts the targetId alias and a free-form payload`() {
        assertThat(readRequest("""{"gameId":7,"actionType":"SUBMIT_VOTE","targetId":"u2"}""", GameActionRequestDto::class))
            .isEqualTo(GameActionRequestDto(7, ActionType.SUBMIT_VOTE, "u2", null))
        assertThat(
            readRequest(
                """{"gameId":7,"actionType":"WITCH_ACT","targetUserId":"u2","payload":{"n":1,"d":1.5,"s":"x","b":true,"z":null}}""",
                GameActionRequestDto::class,
            )
        ).isEqualTo(
            GameActionRequestDto(7, ActionType.WITCH_ACT, "u2", mapOf("n" to 1, "d" to 1.5, "s" to "x", "b" to true, "z" to null))
        )
    }

    @Test
    fun `missing primitive request fields default to zero or false instead of failing`() {
        assertThat(readRequest("""{"actionType":"DAY_ADVANCE"}""", GameActionRequestDto::class))
            .isEqualTo(GameActionRequestDto(0, ActionType.DAY_ADVANCE))
        assertThat(readRequest("""{"roomId":3}""", SetReadyRequest::class)).isEqualTo(SetReadyRequest(false, 3))
    }

    @Test
    fun `missing room config fields fall back to Kotlin defaults`() {
        assertThat(readRequest("""{"config":{"totalPlayers":9,"roles":["SEER","WITCH"]}}""", CreateRoomRequest::class))
            .isEqualTo(CreateRoomRequest(RoomConfigRequest(totalPlayers = 9, roles = listOf(PlayerRole.SEER, PlayerRole.WITCH))))
    }

    @Test
    fun `unknown request fields are ignored`() {
        assertThat(readRequest("""{"roomId":3,"unknownField":true}""", LeaveRoomRequest::class)).isEqualTo(LeaveRoomRequest(3))
    }
}
