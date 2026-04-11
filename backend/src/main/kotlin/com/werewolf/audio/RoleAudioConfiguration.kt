package com.werewolf.audio

import com.werewolf.audio.impl.*
import com.werewolf.model.PlayerRole
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 角色音频配置初始化器
 */
@Component
class RoleAudioConfiguration {

    @EventListener(ApplicationReadyEvent::class)
    fun registerRoleAudioConfigs() {
        val configs = listOf(
            WerewolfAudioConfig(),
            SeerAudioConfig(),
            WitchAudioConfig(),
            GuardAudioConfig(),
            HunterAudioConfig(),
            IdiotAudioConfig(),
            VillagerAudioConfig()
        )

        RoleRegistry.registerAll(configs)

        // 验证所有角色都已注册
        val registeredRoles = RoleRegistry.getRegisteredRoles()

        val missingRoles = PlayerRole.entries - registeredRoles
        if (missingRoles.isNotEmpty()) {
            throw IllegalStateException(
                "Missing audio configuration for roles: $missingRoles"
            )
        }
    }
}