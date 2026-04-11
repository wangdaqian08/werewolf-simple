package com.werewolf.audio.impl

import com.werewolf.audio.RoleAudioConfig
import com.werewolf.model.PlayerRole

data class WitchAudioConfig(
    override val role: PlayerRole = PlayerRole.WITCH,
    override val openEyesAudio: String = "witch_open_eyes.mp3",
    override val closeEyesAudio: String = "witch_close_eyes.mp3",
    override val defaultDelayMs: Long = 5000L
) : RoleAudioConfig