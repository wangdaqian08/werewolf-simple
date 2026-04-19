package com.werewolf.audio

import com.werewolf.model.PlayerRole

/**
 * 角色音频配置接口
 */
interface RoleAudioConfig {
    val role: PlayerRole
    val openEyesAudio: String
    val closeEyesAudio: String
    val defaultDelayMs: Long
}