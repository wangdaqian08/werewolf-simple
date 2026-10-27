package com.werewolf.service

import com.werewolf.dto.*
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * One player's replay of a finished game (玩家视角复盘), built from the same rows as
 * [GameReviewBuilder] but cut down to what the viewer themselves knew.
 *
 * Two rules shape it. The replay stops when the viewer leaves the game: rounds run to
 * their death day, and that day keeps only what happened before they were killed. And a
 * seat's role stays hidden unless the viewer had a way to know it — their own, a wolf
 * teammate's, or one the whole table watched being revealed; a seer check gives the camp
 * alone. Dawn names the dead without naming the cause, because that is all the table hears.
 */
object PlayerReviewBuilder {

    private val mapper = jacksonObjectMapper()
    private val DAY_EVENT_TYPES = setOf("SHERIFF_RESULT", "VOTE_RESULT", "HUNTER_SHOT", "SELF_DESTRUCT", "IDIOT_REVEAL", "BADGE_HANDOVER")

    private class LoggedEvent(val type: String, val day: Int, val payload: Map<String, Any?>)

    fun build(
        game: Game,
        players: List<GamePlayer>,
        nights: List<NightPhase>,
        events: List<GameEvent>,
        night1PerkSaved: Set<String>,
        viewerId: String,
        rewards: Map<String, Int>,
        myItems: List<RewardBreakdown.Item>,
        displayNameFor: (String) -> String,
    ): PlayerReview {
        val me = players.firstOrNull { it.userId == viewerId }
            ?: throw IllegalArgumentException("The viewer did not play this game")
        val byId = players.associateBy { it.userId }

        val logged = events.map { e ->
            @Suppress("UNCHECKED_CAST")
            val payload = mapper.readValue(e.message, Map::class.java) as Map<String, Any?>
            LoggedEvent(e.eventType, (payload["dayNumber"] as Number).toInt(), payload)
        }
        val completed = nights.filter { it.subPhase == NightSubPhase.COMPLETE }.associateBy { it.dayNumber }
        val nightDeaths = logged.filter { it.type == "NIGHT_DEATH" }.groupBy({ it.day }, { it.payload["userId"] as String })
        val eventsByDay = logged.filter { it.type in DAY_EVENT_TYPES }.groupBy { it.day }
        val lastNight = completed.keys.maxOrNull()

        // NIGHT_DEATH is written at the dawn reveal; a game cancelled before it has none for its last night.
        fun deathsAt(day: Int): List<String> = nightDeaths[day]
            ?: completed[day]?.takeIf { game.winner == null && day == lastNight }
                ?.let { NightOrchestrator.computeKills(it, night1PerkSaved) }
            ?: emptyList()

        // The viewer dies either at a dawn — and then hears nothing of that day — or part way
        // through a day, and then hears it up to the event that took them out.
        val diedOnDay = me.diedDay
        fun killsViewer(e: LoggedEvent) = when (e.type) {
            "VOTE_RESULT" -> e.payload["eliminatedUserId"] == viewerId
            "HUNTER_SHOT" -> e.payload["targetUserId"] == viewerId
            "SELF_DESTRUCT" -> e.payload["userId"] == viewerId || e.payload["takenUserId"] == viewerId
            else -> false
        }
        fun visibleEvents(day: Int): List<LoggedEvent> {
            val all = eventsByDay[day].orEmpty()
            if (day != diedOnDay) return all
            if (viewerId in deathsAt(day)) return emptyList()
            val last = all.indexOfFirst(::killsViewer)
            return if (last >= 0) all.take(last + 1) else all
        }

        val days = (completed.keys + eventsByDay.keys).sorted().filter { diedOnDay == null || it <= diedOnDay }

        // Knowledge is gathered over exactly the rounds the viewer lived through.
        val knownRoles = mutableMapOf(me.userId to me.role)
        val knownCamps = mutableMapOf<String, KnownCamp>()
        if (me.role.isWolf) players.filter { it.role.isWolf }.forEach { knownRoles[it.userId] = it.role }
        if (me.role == PlayerRole.SEER) days.mapNotNull(completed::get).forEach { n ->
            n.seerCheckedUserId?.let { knownCamps[it] = if (n.seerResultIsWerewolf == true) KnownCamp.WOLF else KnownCamp.GOOD }
        }
        days.flatMap(::visibleEvents).forEach { e ->
            val subject = e.payload["userId"] as? String ?: return@forEach
            when (e.type) {
                // Only a wolf may self-destruct; the idiot flips face up for everyone to see.
                "SELF_DESTRUCT" -> knownCamps[subject] = KnownCamp.WOLF
                "IDIOT_REVEAL" -> knownRoles[subject] = PlayerRole.IDIOT
            }
        }

        fun seat(id: String): PlayerSeat? = byId[id]?.let { p ->
            val role = knownRoles[p.userId]
            PlayerSeat(p.userId, displayNameFor(p.userId), p.seatIndex, role, knownCamps[p.userId].takeIf { role == null })
        }

        // The working is shown only while it still adds up to what was actually banked;
        // if a rate was changed after settlement, the total stands alone and nothing is
        // pinned to individual actions.
        val total = rewards[viewerId]
        val shown = myItems.takeIf { total != null && it.sumOf(RewardBreakdown.Item::amount) == total }.orEmpty()
        val voteItem = shown.firstOrNull { it.reason == RewardReason.WOLF_VOTE }
        val sheriffLeft = intArrayOf(shown.firstOrNull { it.reason == RewardReason.SHERIFF }?.amount ?: 0)

        val rounds = days.map { day ->
            PlayerRound(
                day = day,
                night = completed[day]?.let { night(it, me, players, byId, deathsAt(day), ::seat) },
                events = dayEvents(
                    visibleEvents(day), ::seat, viewerId,
                    voteItem?.each?.takeIf { day in voteItem.onDays },
                    sheriffLeft,
                ),
            )
        }
        return PlayerReview(
            winner = game.winner,
            me = seat(viewerId)!!,
            seats = players.sortedBy { it.seatIndex }.mapNotNull { seat(it.userId) },
            rounds = rounds,
            diedOnDay = diedOnDay,
            reward = total?.let { t ->
                MyReward(t, shown.map { RewardItem(it.reason, it.amount, it.count, it.each, it.days, it.perDay) })
            },
        )
    }

    private fun night(
        n: NightPhase,
        me: GamePlayer,
        players: List<GamePlayer>,
        byId: Map<String, GamePlayer>,
        died: List<String>,
        seat: (String) -> PlayerSeat?,
    ): PlayerNight {
        // Dying during night N (diedDay == N) still means they acted that night.
        fun deadAt(p: GamePlayer) = (p.diedDay ?: Int.MAX_VALUE) < n.dayNumber
        val target = n.wolfTargetUserId
        val action = when {
            me.role.isWolf -> MyNightAction.Wolf(
                teammates = players.filter { it.role.isWolf && !deadAt(it) }.sortedBy { it.seatIndex }.mapNotNull { seat(it.userId) },
                target = target?.let(seat),
                selfKill = target != null && byId[target]?.role?.isWolf == true,
            )
            me.role == PlayerRole.SEER -> MyNightAction.Seer(n.seerCheckedUserId?.let(seat), n.seerResultIsWerewolf ?: false)
            // The witch is shown the night's attack before she chooses.
            me.role == PlayerRole.WITCH -> MyNightAction.Witch(
                attacked = target?.let(seat),
                antidote = target?.takeIf { n.witchAntidoteUsed }?.let(seat),
                poison = n.witchPoisonTargetUserId?.let(seat),
            )
            me.role == PlayerRole.GUARD -> MyNightAction.Guard(n.guardTargetUserId?.let(seat))
            else -> null
        }
        // Only the wolves and the witch are told an attack happened, so only they can see it fail.
        val knewOfAttack = me.role.isWolf || me.role == PlayerRole.WITCH
        val saved = target?.takeIf { it !in died && knewOfAttack }?.let(seat)
        return PlayerNight(action, PublicDawn(died.mapNotNull(seat).sortedBy { it.seatIndex }, saved))
    }

    @Suppress("UNCHECKED_CAST")
    private fun dayEvents(
        events: List<LoggedEvent>,
        seat: (String) -> PlayerSeat?,
        viewerId: String,
        wolfVotePoints: Int?,
        sheriffLeft: IntArray,
    ): List<PlayerDayEvent> {
        fun ref(id: Any?) = (id as? String)?.let(seat)
        fun refsOf(list: Any?) = (list as List<Map<String, Any?>>?).orEmpty().mapNotNull { ref(it["userId"]) }
        val roundsSoFar = mutableMapOf<VoteKind, Int>()
        // The vote bonus is for the day's final word, so it rides on the last exile vote of the day.
        val lastExile = events.indexOfLast { it.type == "VOTE_RESULT" }
        /** The badge pays once, on the first event that put it in the viewer's hands. */
        fun claimSheriff() = sheriffLeft[0].takeIf { it > 0 }?.also { sheriffLeft[0] = 0 }
        fun vote(kind: VoteKind, p: Map<String, Any?>, resultId: Any?, points: Int?): PlayerDayEvent.Vote {
            val tally = (p["tally"] as List<Map<String, Any?>>).mapNotNull { t ->
                ref(t["userId"])?.let { PlayerTally(it, (t["votes"] as Number).toDouble(), refsOf(t["voters"])) }
            }
            val round = roundsSoFar.merge(kind, 1) { a, b -> a + b }!!
            val result = ref(resultId)
            // SheriffService.appoint logs the tied (or empty) tally with the host's pick as winner.
            val top = tally.maxOfOrNull { it.votes }
            val appointed = kind == VoteKind.SHERIFF && result != null && tally.singleOrNull { it.votes == top }?.target != result
            return PlayerDayEvent.Vote(kind, round, tally, refsOf(p["abstainVoters"]), result, appointed, points)
        }
        return events.mapIndexedNotNull { i, e ->
            val p = e.payload
            when (e.type) {
                "SHERIFF_RESULT" ->
                    vote(VoteKind.SHERIFF, p, p["winnerUserId"], if (p["winnerUserId"] == viewerId) claimSheriff() else null)
                "VOTE_RESULT" ->
                    vote(VoteKind.EXILE, p, p["eliminatedUserId"], wolfVotePoints?.takeIf { i == lastExile })
                "HUNTER_SHOT" -> ref(p["hunterUserId"])?.let { h -> ref(p["targetUserId"])?.let { PlayerDayEvent.HunterShot(h, it) } }
                "SELF_DESTRUCT" -> ref(p["userId"])?.let { PlayerDayEvent.SelfDestruct(it, ref(p["takenUserId"])) }
                "IDIOT_REVEAL" -> ref(p["userId"])?.let { PlayerDayEvent.IdiotReveal(it) }
                "BADGE_HANDOVER" -> ref(p["fromUserId"])?.let {
                    PlayerDayEvent.BadgeHandover(it, ref(p["toUserId"]), if (p["toUserId"] == viewerId) claimSheriff() else null)
                }
                else -> null
            }
        }
    }
}
