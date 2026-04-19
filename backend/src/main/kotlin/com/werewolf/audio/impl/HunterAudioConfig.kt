package com.werewolf.audio.impl

import com.werewolf.audio.RoleAudioConfig
import com.werewolf.model.PlayerRole

data class HunterAudioConfig(
    override val role: PlayerRole = PlayerRole.HUNTER,
    override val openEyesAudio: String = "hunter_open_eyes.mp3",
    override val closeEyesAudio: String = "hunter_close_eyes.mp3",
    override val defaultDelayMs: Long = 5000L
) : RoleAudioConfig