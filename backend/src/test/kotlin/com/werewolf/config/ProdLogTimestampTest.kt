package com.werewolf.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.LoggingEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import java.time.Instant

/**
 * Containers run in UTC (and the DB stores UTC wall-clock times), so prod log
 * lines must carry the zone themselves: 10 PM in Sydney printed as 11:16 hid
 * what happened when. Logback silently falls back to GMT on an unknown zone id,
 * so render the real patterns instead of string-matching them.
 */
class ProdLogTimestampTest {

    private val props = YamlPropertySourceLoader()
        .load("application-prod.yml", ClassPathResource("application-prod.yml"))
        .first()

    private fun render(pattern: String, at: String): String {
        val layout = PatternLayout().apply {
            context = LoggerContext()
            this.pattern = pattern
            start()
        }
        val event = LoggingEvent().apply {
            timeStamp = Instant.parse(at).toEpochMilli()
            level = Level.INFO
            loggerName = "test"
            threadName = "main"
            message = "msg"
        }
        return layout.doLayout(event)
    }

    @Test
    fun `console log lines show Sydney time with its offset`() {
        val console = props.getProperty("logging.pattern.console") as String
        assertTrue(render(console, "2026-10-24T11:16:20Z").startsWith("2026-10-24 22:16:20.000+11:00 "))
        assertTrue(render(console, "2026-07-01T00:00:00Z").startsWith("2026-07-01 10:00:00.000+10:00 "))
    }

    @Test
    fun `file log lines use the same Sydney timestamp`() {
        val dateFormat = props.getProperty("logging.pattern.dateformat") as String?
        // Spring Boot's default FILE_LOG_PATTERN embeds it as %d{${LOG_DATEFORMAT_PATTERN}}.
        assertEquals("2026-10-24 22:16:20.000+11:00", render("%d{$dateFormat}", "2026-10-24T11:16:20Z"))
    }
}
