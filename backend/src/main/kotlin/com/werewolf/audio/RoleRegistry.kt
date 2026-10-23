package com.werewolf.audio

import com.werewolf.model.PlayerRole
import org.springframework.stereotype.Component

/**
 * 角色音频配置注册表
 */
@Component
object RoleRegistry {

    private val roleConfigs: MutableMap<PlayerRole, RoleAudioConfig> = mutableMapOf()

    fun registerAll(configs: List<RoleAudioConfig>) {
        configs.forEach { config ->
            roleConfigs[config.role] = config
        }
    }

    fun getOpenEyesAudio(role: PlayerRole): String? {
        return roleConfigs[role]?.openEyesAudio
    }

    fun getCloseEyesAudio(role: PlayerRole): String? {
        return roleConfigs[role]?.closeEyesAudio
    }

    fun getRegisteredRoles(): List<PlayerRole> {
        return roleConfigs.keys.toList()
    }
}