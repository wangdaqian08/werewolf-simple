package com.werewolf.audio.impl

import com.werewolf.audio.RoleAudioConfig
import com.werewolf.model.PlayerRole

data class GuardAudioConfig(
    override val role: PlayerRole = PlayerRole.GUARD,
    override val openEyesAudio: String = "guard_open_eyes.mp3",
    override val closeEyesAudio: String = "guard_close_eyes.mp3",
    override val defaultDelayMs: Long = 5000L
) : RoleAudioConfig