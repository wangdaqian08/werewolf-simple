package com.werewolf.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource

/**
 * The host timer presets are configured once, in the base application.yml,
 * and every screen (sheriff speech, day discussion) uses that single list.
 */
class HostTimerPropertiesTest {

    @Test
    fun `base application yml configures the 75s and 3 minute host timer presets`() {
        val env = StandardEnvironment()
        YamlPropertySourceLoader().load("application.yml", ClassPathResource("application.yml"))
            .forEach { env.propertySources.addLast(it) }

        val props = Binder.get(env).bind("werewolf.host-timer", HostTimerProperties::class.java).get()

        assertThat(props.presetsSeconds).containsExactly(75L, 180L)
    }
}
