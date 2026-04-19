package com.werewolf.config

import com.werewolf.model.PlayerRole
import com.werewolf.model.RoleDelayConfig
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Overrides for the timing constants that drive the night-phase coroutine.
 *
 * All properties are nullable; `null` means "use the compile-time default" (the
 * values on [RoleDelayConfig] companion objects and on [com.werewolf.game.night.NightOrchestrator]).
 * Production runs without any `werewolf.timing.*` keys set and gets the defaults.
 *
 * Test profile (`src/test/resources/application-test.yml`) sets all of these to
 * small values so integration tests that traverse the real coroutine complete
 * in seconds rather than minutes. The tunable points:
 *
 *   deadRoleDelayMs           — how long the role loop idles on a dead role's
 *                               first sub-phase to mask its death timing-wise.
 *   audioWarmupMs             — pause after an open-eyes broadcast before the
 *                               sub-phase is committed to DB / deferred-await.
 *   audioCooldownMs           — pause after a close-eyes broadcast.
 *   interRoleGapMs            — pause between one role ending and the next
 *                               starting; what guarantees non-overlapping audio.
 *   nightInitAudioDelayMs     — pause after night starts before the role loop
 *                               begins, so goes_dark + wolf_howl play fully.
 *   waitingDelayMs            — how long WAITING sub-phase idles for sheriff-
 *                               election games before the first real role.
 */
@ConfigurationProperties(prefix = "werewolf.timing")
data class GameTimingProperties(
    val deadRoleDelayMs: Long? = null,
    val audioWarmupMs: Long? = null,
    val audioCooldownMs: Long? = null,
    val interRoleGapMs: Long? = null,
    val nightInitAudioDelayMs: Long? = null,
    val waitingDelayMs: Long? = null,
) {
    /**
     * Apply the configured overrides to the given role's default [RoleDelayConfig].
     * Null overrides preserve the role-specific default (e.g. WEREWOLF_DEFAULT).
     */
    fun applyTo(role: PlayerRole): RoleDelayConfig {
        val base = RoleDelayConfig.getDefaultForRole(role)
        return base.copy(
            deadRoleDelayMs = deadRoleDelayMs ?: base.deadRoleDelayMs,
            audioWarmupMs = audioWarmupMs ?: base.audioWarmupMs,
            audioCooldownMs = audioCooldownMs ?: base.audioCooldownMs,
            interRoleGapMs = interRoleGapMs ?: base.interRoleGapMs,
        )
    }
}
