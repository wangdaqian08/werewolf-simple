package com.werewolf.audio.impl

import com.werewolf.audio.RoleAudioConfig
import com.werewolf.model.PlayerRole

data class GargoyleAudioConfig(
    override val role: PlayerRole = PlayerRole.GARGOYLE,
    override val openEyesAudio: String = "gargoyle_open_eyes.mp3",
    override val closeEyesAudio: String = "gargoyle_close_eyes.mp3",
    override val defaultDelayMs: Long = 5000L
) : RoleAudioConfig
