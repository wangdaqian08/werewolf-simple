package com.werewolf.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Single source of truth for the host-controlled speech countdown presets
 * (sheriff campaign speech and day discussion). Configured in application.yml
 * under `werewolf.host-timer.presets-seconds`; the backend accepts only these
 * durations and sends the list to the frontend in the game state.
 */
@ConfigurationProperties(prefix = "werewolf.host-timer")
data class HostTimerProperties(
    val presetsSeconds: List<Long>,
)
