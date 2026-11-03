package com.werewolf.audio.impl

import com.werewolf.audio.RoleAudioConfig
import com.werewolf.model.PlayerRole

data class MechanicalWolfAudioConfig(
    override val role: PlayerRole = PlayerRole.MECHANICAL_WOLF,
    override val openEyesAudio: String = "mech_wolf_open_eyes.mp3",
    override val closeEyesAudio: String = "mech_wolf_close_eyes.mp3",
    override val defaultDelayMs: Long = 5000L
) : RoleAudioConfig
