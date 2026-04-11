package com.werewolf.audio

import com.werewolf.model.PlayerRole
import org.springframework.stereotype.Component

/**
 * 角色音频配置注册表
 */
@Component
object RoleRegistry {

    private val roleConfigs: MutableMap<PlayerRole, RoleAudioConfig> = mutableMapOf()

    fun register(config: RoleAudioConfig) {
        roleConfigs[config.role] = config
    }

    fun registerAll(configs: List<RoleAudioConfig>) {
        configs.forEach { config ->
            roleConfigs[config.role] = config
        }
    }

    fun getAudioConfig(role: PlayerRole): RoleAudioConfig? {
        return roleConfigs[role]
    }

    fun getOpenEyesAudio(role: PlayerRole): String? {
        return roleConfigs[role]?.openEyesAudio
    }

    fun getCloseEyesAudio(role: PlayerRole): String? {
        return roleConfigs[role]?.closeEyesAudio
    }

    fun getDefaultDelayMs(role: PlayerRole): Long? {
        return roleConfigs[role]?.defaultDelayMs
    }

    fun isRegistered(role: PlayerRole): Boolean {
        return roleConfigs.containsKey(role)
    }

    fun getRegisteredRoles(): List<PlayerRole> {
        return roleConfigs.keys.toList()
    }

    fun clear() {
        roleConfigs.clear()
    }
}