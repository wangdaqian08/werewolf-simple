package com.werewolf.model

/**
 * 角色延迟配置
 * 用于控制夜阶段中每个角色的操作窗口和死亡角色模拟延迟
 */
data class RoleDelayConfig(
    /**
     * 活跃角色的操作窗口时间（毫秒）
     * 玩家在此时间内可以提交夜间行动
     */
    val actionWindowMs: Long = 30000L,

    /**
     * 死亡角色的模拟延迟时间（毫秒）
     * 当角色已死亡时，backend 会等待此时间以模拟正常操作
     * 这样可以防止玩家通过时序推断出角色死亡
     */
    val deadRoleDelayMs: Long = 25000L,

    /**
     * 睁眼音频预热时间（毫秒）
     * 播放 open_eyes 音频后，等待此时间再更新 DB 子阶段
     */
    val audioWarmupMs: Long = 1500L,

    /**
     * 闭眼音频冷却时间（毫秒）
     * 播放 close_eyes 音频后，等待此时间再继续
     */
    val audioCooldownMs: Long = 2000L,

    /**
     * 角色间隔时间（毫秒）
     * 一个角色闭眼后，到下一个角色睁眼之前的间隔
     */
    val interRoleGapMs: Long = 3000L,
) {
    companion object {
        /**
         * 狼人默认配置：30秒操作窗口，25秒死亡模拟
         */
        val WEREWOLF_DEFAULT = RoleDelayConfig(30000L, 25000L)

        /**
         * 预言家默认配置：20秒操作窗口，15秒死亡模拟
         */
        val SEER_DEFAULT = RoleDelayConfig(20000L, 15000L)

        /**
         * 女巫默认配置：25秒操作窗口，20秒死亡模拟
         */
        val WITCH_DEFAULT = RoleDelayConfig(25000L, 20000L)

        /**
         * 守卫默认配置：20秒操作窗口，15秒死亡模拟
         */
        val GUARD_DEFAULT = RoleDelayConfig(20000L, 15000L)

        /**
         * 获取角色的默认配置
         */
        fun getDefaultForRole(role: PlayerRole): RoleDelayConfig {
            return when (role) {
                PlayerRole.WEREWOLF -> WEREWOLF_DEFAULT
                PlayerRole.SEER -> SEER_DEFAULT
                PlayerRole.WITCH -> WITCH_DEFAULT
                PlayerRole.GUARD -> GUARD_DEFAULT
                else -> RoleDelayConfig(30000L, 25000L) // 默认值
            }
        }
    }
}

/**
 * 游戏配置
 * 包含游戏房间的所有配置信息，存储在 Room.config JSONB 字段中
 */
data class GameConfig(
    /**
     * 各角色的延迟配置
     * 键为角色类型，值为对应的延迟配置
     */
    val roleDelays: Map<PlayerRole, RoleDelayConfig> = mapOf(
        PlayerRole.WEREWOLF to RoleDelayConfig.WEREWOLF_DEFAULT,
        PlayerRole.SEER to RoleDelayConfig.SEER_DEFAULT,
        PlayerRole.WITCH to RoleDelayConfig.WITCH_DEFAULT,
        PlayerRole.GUARD to RoleDelayConfig.GUARD_DEFAULT
    )
) {
    /**
     * 获取指定角色的延迟配置
     */
    fun getDelayForRole(role: PlayerRole): RoleDelayConfig {
        return roleDelays[role] ?: RoleDelayConfig.getDefaultForRole(role)
    }

    companion object {
        /**
         * 创建默认的游戏配置
         */
        fun createDefault(): GameConfig {
            return GameConfig()
        }
    }
}