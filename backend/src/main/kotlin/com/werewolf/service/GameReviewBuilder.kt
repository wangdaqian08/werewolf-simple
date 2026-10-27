package com.werewolf.service

import com.werewolf.dto.*
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * God's-eye replay of a finished game (上帝视角复盘): every completed night with
 * each role's action and the dawn outcome, then the day's public events, with
 * every player's true role.
 *
 * Nights come from the NightPhase rows; deaths and day events from the action
 * log that [ActionLogService] writes; a 首夜免死 save only from the perk record
 * ([night1PerkSaved]). Names come from [displayNameFor], not the nicknames
 * stored in the event payloads.
 */
object GameReviewBuilder {

    private val mapper = jacksonObjectMapper()
    private val DAY_EVENT_TYPES = setOf("SHERIFF_RESULT", "VOTE_RESULT", "HUNTER_SHOT", "SELF_DESTRUCT", "IDIOT_REVEAL", "BADGE_HANDOVER")

    private class LoggedEvent(val type: String, val day: Int, val payload: Map<String, Any?>)

    fun build(
        game: Game,
        players: List<GamePlayer>,
        nights: List<NightPhase>,
        events: List<GameEvent>,
        night1PerkSaved: Set<String>,
        rewards: Map<String, Int>,
        displayNameFor: (String) -> String,
    ): GameReview {
        val refs = players.associate { it.userId to ReviewPlayer(it.userId, displayNameFor(it.userId), it.seatIndex, it.role) }
        val logged = events.map { e ->
            @Suppress("UNCHECKED_CAST")
            val payload = mapper.readValue(e.message, Map::class.java) as Map<String, Any?>
            LoggedEvent(e.eventType, (payload["dayNumber"] as Number).toInt(), payload)
        }
        val completed = nights.filter { it.subPhase == NightSubPhase.COMPLETE }.associateBy { it.dayNumber }
        val nightDeaths = logged.filter { it.type == "NIGHT_DEATH" }.groupBy({ it.day }, { it.payload["userId"] as String })

        val eventsByDay = logged.filter { it.type in DAY_EVENT_TYPES }.groupBy { it.day }
        val lastNight = completed.keys.maxOrNull()
        val rounds = (completed.keys + eventsByDay.keys).sorted().map { day ->
            ReviewRound(
                day = day,
                night = completed[day]?.let { n ->
                    // NIGHT_DEATH is written at the dawn reveal; a game cancelled before it has none for its last night.
                    val died = nightDeaths[day]
                        ?: if (game.winner == null && day == lastNight) NightOrchestrator.computeKills(n, night1PerkSaved) else emptyList()
                    night(n, players, refs, died, night1PerkSaved)
                },
                events = dayEvents(eventsByDay[day].orEmpty(), refs),
            )
        }
        val bySeat = players.sortedBy { it.seatIndex }
        return GameReview(
            game.winner,
            bySeat.map { refs.getValue(it.userId) },
            rounds,
            bySeat.mapNotNull { p -> rewards[p.userId]?.let { ReviewReward(refs.getValue(p.userId), it) } },
        )
    }

    private fun night(
        n: NightPhase,
        players: List<GamePlayer>,
        refs: Map<String, ReviewPlayer>,
        died: List<String>,
        night1PerkSaved: Set<String>,
    ): ReviewNight {
        // Every death path sets diedDay; dying during night N (diedDay == N) still means they acted that night.
        fun deadAt(p: GamePlayer) = (p.diedDay ?: Int.MAX_VALUE) < n.dayNumber
        fun holder(role: PlayerRole) = players.firstOrNull { it.role == role }
        val target = n.wolfTargetUserId?.let(refs::get)
        return ReviewNight(
            wolf = WolfTurn(
                actors = players.filter { it.role.isWolf && !deadAt(it) }.sortedBy { it.seatIndex }.map { refs.getValue(it.userId) },
                action = when {
                    target == null -> WolfAction.SKIP
                    target.role.isWolf -> WolfAction.SELF_KILL
                    else -> WolfAction.KILL
                },
                target = target,
            ),
            seer = holder(PlayerRole.SEER)?.let {
                SeerTurn(refs.getValue(it.userId), deadAt(it), n.seerCheckedUserId?.let(refs::get), n.seerResultIsWerewolf ?: false)
            },
            witch = holder(PlayerRole.WITCH)?.let {
                WitchTurn(refs.getValue(it.userId), deadAt(it), target.takeIf { n.witchAntidoteUsed }, n.witchPoisonTargetUserId?.let(refs::get))
            },
            guard = holder(PlayerRole.GUARD)?.let {
                GuardTurn(refs.getValue(it.userId), deadAt(it), n.guardTargetUserId?.let(refs::get))
            },
            dawn = dawn(n, refs, died, night1PerkSaved),
        )
    }

    private fun dawn(n: NightPhase, refs: Map<String, ReviewPlayer>, died: List<String>, night1PerkSaved: Set<String>): Dawn {
        val deaths = died.mapNotNull(refs::get).sortedBy { it.seatIndex }.map { p ->
            NightDeath(p, when {
                p.userId == n.witchPoisonTargetUserId -> DeathCause.POISON
                p.userId == n.wolfTargetUserId && n.witchAntidoteUsed && n.guardTargetUserId == p.userId -> DeathCause.BOTH_SAVES
                else -> DeathCause.WOLF
            })
        }
        val saved = n.wolfTargetUserId?.takeIf { it !in died }?.let(refs::get)?.let { p ->
            when {
                n.dayNumber == 1 && p.userId in night1PerkSaved -> SaveSource.PERK
                n.witchAntidoteUsed -> SaveSource.ANTIDOTE
                n.guardTargetUserId == p.userId -> SaveSource.GUARD
                else -> null
            }?.let { NightSave(p, it) }
        }
        return Dawn(deaths, saved)
    }

    @Suppress("UNCHECKED_CAST")
    private fun dayEvents(events: List<LoggedEvent>, refs: Map<String, ReviewPlayer>): List<ReviewDayEvent> {
        fun ref(id: Any?) = (id as? String)?.let(refs::get)
        fun refsOf(list: Any?) = (list as List<Map<String, Any?>>?).orEmpty().mapNotNull { ref(it["userId"]) }
        val roundsSoFar = mutableMapOf<VoteKind, Int>()
        fun vote(kind: VoteKind, p: Map<String, Any?>, resultId: Any?): ReviewDayEvent.Vote {
            val tally = (p["tally"] as List<Map<String, Any?>>).mapNotNull { t ->
                ref(t["userId"])?.let { TallyEntry(it, (t["votes"] as Number).toDouble(), refsOf(t["voters"])) }
            }
            val round = roundsSoFar.merge(kind, 1) { a, b -> a + b }!!
            val result = ref(resultId)
            val play = if (kind == VoteKind.EXILE) wolfPlay(tally) else null
            // SheriffService.appoint logs the tied (or empty) tally with the host's pick as winner.
            val top = tally.maxOfOrNull { it.votes }
            val appointed = kind == VoteKind.SHERIFF && result != null && tally.singleOrNull { it.votes == top }?.target != result
            return ReviewDayEvent.Vote(kind, round, tally, refsOf(p["abstainVoters"]), result, appointed, play)
        }
        return events.mapNotNull { e ->
            val p = e.payload
            when (e.type) {
                "SHERIFF_RESULT" -> vote(VoteKind.SHERIFF, p, p["winnerUserId"])
                "VOTE_RESULT" -> vote(VoteKind.EXILE, p, p["eliminatedUserId"])
                "HUNTER_SHOT" -> ref(p["hunterUserId"])?.let { h -> ref(p["targetUserId"])?.let { ReviewDayEvent.HunterShot(h, it) } }
                "SELF_DESTRUCT" -> ref(p["userId"])?.let { ReviewDayEvent.SelfDestruct(it, ref(p["takenUserId"])) }
                "IDIOT_REVEAL" -> ref(p["userId"])?.let { ReviewDayEvent.IdiotReveal(it) }
                "BADGE_HANDOVER" -> ref(p["fromUserId"])?.let { ReviewDayEvent.BadgeHandover(it, ref(p["toUserId"])) }
                else -> null
            }
        }
    }

    /** Exile votes only. 倒钩: a wolf voted out a teammate. 冲票: 2+ wolves voted, all for the same good player. */
    private fun wolfPlay(tally: List<TallyEntry>): WolfPlay? {
        val wolfTargets = tally.flatMap { t -> t.voters.filter { it.role.isWolf }.map { t.target } }
        return when {
            wolfTargets.any { it.role.isWolf } -> WolfPlay.BUS
            wolfTargets.size >= 2 && wolfTargets.distinct().size == 1 -> WolfPlay.PUSH
            else -> null
        }
    }
}
