package com.werewolf.audio.impl

import com.werewolf.audio.RoleAudioConfig
import com.werewolf.model.PlayerRole

data class VillagerAudioConfig(
    override val role: PlayerRole = PlayerRole.VILLAGER,
    override val openEyesAudio: String = "villager_open_eyes.mp3",
    override val closeEyesAudio: String = "villager_close_eyes.mp3",
    override val defaultDelayMs: Long = 5000L
) : RoleAudioConfig