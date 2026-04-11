package com.werewolf.audio.impl

import com.werewolf.audio.RoleAudioConfig
import com.werewolf.model.PlayerRole

data class IdiotAudioConfig(
    override val role: PlayerRole = PlayerRole.IDIOT,
    override val openEyesAudio: String = "idiot_open_eyes.mp3",
    override val closeEyesAudio: String = "idiot_close_eyes.mp3",
    override val defaultDelayMs: Long = 5000L
) : RoleAudioConfig