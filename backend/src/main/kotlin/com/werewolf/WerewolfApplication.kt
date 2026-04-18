package com.werewolf

import com.werewolf.config.GameTimingProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(GameTimingProperties::class)
class WerewolfApplication

fun main(args: Array<String>) {
    runApplication<WerewolfApplication>(*args)
}
