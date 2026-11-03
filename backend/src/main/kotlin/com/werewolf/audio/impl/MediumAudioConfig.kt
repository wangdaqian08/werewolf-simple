package com.werewolf.audio.impl

import com.werewolf.audio.RoleAudioConfig
import com.werewolf.model.PlayerRole

data class MediumAudioConfig(
    override val role: PlayerRole = PlayerRole.MEDIUM,
    override val openEyesAudio: String = "medium_open_eyes.mp3",
    override val closeEyesAudio: String = "medium_close_eyes.mp3",
    override val defaultDelayMs: Long = 5000L
) : RoleAudioConfig
