package com.werewolf.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Kotlin 协程配置
 * 提供应用程序级别的 CoroutineScope，用于管理协程生命周期
 */
@Configuration
class CoroutineConfig {

    /**
     * 创建应用程序级别的 CoroutineScope
     * 使用 Dispatchers.Default 作为调度器，适合 CPU 密集型任务
     * 使用 SupervisorJob 作为父 Job，确保子协程的失败不会影响其他子协程
     */
    @Bean
    fun coroutineScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.Default + SupervisorJob())
    }
}