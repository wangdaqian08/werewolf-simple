package com.werewolf

import com.werewolf.config.GameTimingProperties
import com.werewolf.config.PaymentProperties
import com.werewolf.config.RewardProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(GameTimingProperties::class, RewardProperties::class, PaymentProperties::class)
class WerewolfApplication

fun main(args: Array<String>) {
    runApplication<WerewolfApplication>(*args)
}
