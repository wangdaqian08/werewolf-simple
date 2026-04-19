package com.werewolf.audio.impl

import com.werewolf.audio.RoleAudioConfig
import com.werewolf.model.PlayerRole

data class WerewolfAudioConfig(
    override val role: PlayerRole = PlayerRole.WEREWOLF,
    override val openEyesAudio: String = "wolf_open_eyes.mp3",
    override val closeEyesAudio: String = "wolf_close_eyes.mp3",
    override val defaultDelayMs: Long = 5000L
) : RoleAudioConfig