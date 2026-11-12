package com.werewolf.audio.impl

import com.werewolf.audio.RoleAudioConfig
import com.werewolf.model.PlayerRole

data class GravekeeperAudioConfig(
    override val role: PlayerRole = PlayerRole.GRAVEKEEPER,
    override val openEyesAudio: String = "gravekeeper_open_eyes.mp3",
    override val closeEyesAudio: String = "gravekeeper_close_eyes.mp3",
    override val defaultDelayMs: Long = 5000L
) : RoleAudioConfig
