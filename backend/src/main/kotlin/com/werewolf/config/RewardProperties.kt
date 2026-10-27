package com.werewolf.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Credit amounts granted at game settlement (see RewardSettlementService).
 * Symmetric across factions: winners get [winBonus], losing villager-side
 * players get [participation], losing wolves get [wolfPerDaySurvived] per day
 * they stayed alive — so a wolf eliminated day 1 earns less than one who
 * lasted to day 4.
 *
 * On top of that base, [sheriffBonus] goes to everyone who wore the badge, and
 * [wolfVoteBonus] to each day a player's exile vote landed on a wolf.
 */
@ConfigurationProperties(prefix = "werewolf.rewards")
data class RewardProperties(
    val winBonus: Int = 20,
    val participation: Int = 5,
    val wolfPerDaySurvived: Int = 4,
    val sheriffBonus: Int = 5,
    val wolfVoteBonus: Int = 3,
)
