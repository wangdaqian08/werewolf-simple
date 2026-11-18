package com.werewolf.audio.impl

import com.werewolf.audio.RoleAudioConfig
import com.werewolf.model.PlayerRole

data class WolfBeautyAudioConfig(
    override val role: PlayerRole = PlayerRole.WOLF_BEAUTY,
    override val openEyesAudio: String = "wolf_beauty_open_eyes.mp3",
    override val closeEyesAudio: String = "wolf_beauty_close_eyes.mp3",
    override val defaultDelayMs: Long = 5000L
) : RoleAudioConfig
