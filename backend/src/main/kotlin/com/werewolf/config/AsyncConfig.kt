package com.werewolf.config

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
@EnableAsync
@EnableScheduling
class AsyncConfig {

    val log: Logger = LoggerFactory.getLogger(AsyncConfig::class.java)
    @Bean(name = ["taskExecutor"])
    @Primary
    fun taskExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.setCorePoolSize(5)
        executor.setMaxPoolSize(10)
        executor.setQueueCapacity(100)
        executor.setThreadNamePrefix("async-")
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(60)
        executor.initialize()
        log.info("[AsyncConfig] Task executor initialized with corePoolSize=5, maxPoolSize=10")
        return executor
    }
}
