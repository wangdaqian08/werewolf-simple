package com.werewolf.audio.impl

import com.werewolf.audio.RoleAudioConfig
import com.werewolf.model.PlayerRole

// The knight has no night turn, so these files are never played; RoleAudioConfiguration just requires one config per role.
data class KnightAudioConfig(
    override val role: PlayerRole = PlayerRole.KNIGHT,
    override val openEyesAudio: String = "knight_open_eyes.mp3",
    override val closeEyesAudio: String = "knight_close_eyes.mp3",
    override val defaultDelayMs: Long = 5000L
) : RoleAudioConfig