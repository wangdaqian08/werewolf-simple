package com.werewolf.audio.impl

import com.werewolf.audio.RoleAudioConfig
import com.werewolf.model.PlayerRole

data class SeerAudioConfig(
    override val role: PlayerRole = PlayerRole.SEER,
    override val openEyesAudio: String = "seer_open_eyes.mp3",
    override val closeEyesAudio: String = "seer_close_eyes.mp3",
    override val defaultDelayMs: Long = 5000L
) : RoleAudioConfig