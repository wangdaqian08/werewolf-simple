package com.werewolf.audio.impl

import com.werewolf.audio.RoleAudioConfig
import com.werewolf.model.PlayerRole

// Wakes with the wolves (no turn of its own), so it shares the wolf cues.
data class WhiteWolfKingAudioConfig(
    override val role: PlayerRole = PlayerRole.WHITE_WOLF_KING,
    override val openEyesAudio: String = "wolf_open_eyes.mp3",
    override val closeEyesAudio: String = "wolf_close_eyes.mp3",
    override val defaultDelayMs: Long = 5000L
) : RoleAudioConfig
