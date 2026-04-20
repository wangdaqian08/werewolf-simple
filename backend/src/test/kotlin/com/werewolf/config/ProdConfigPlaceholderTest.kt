package com.werewolf.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource

/**
 * Hardening checks for prod configuration — verifies that the base
 * `application.yml` contains NO insecure defaults for JWT_SECRET or
 * GOOGLE_REDIRECT_URI, and that those values are env-driven.
 *
 * Does not spin up a full Spring Boot context; just loads the YAML and
 * evaluates placeholders against a controlled environment.
 */
class ProdConfigPlaceholderTest {

    private fun loadBaseYaml(): List<PropertySource<*>> =
        YamlPropertySourceLoader().load("application.yml", ClassPathResource("application.yml"))

    private fun envWith(vars: Map<String, String>): StandardEnvironment {
        val env = StandardEnvironment()
        val sources: MutablePropertySources = env.propertySources
        // Higher precedence than the yaml so that set vars win.
        sources.addFirst(MapPropertySource("testEnv", vars))
        loadBaseYaml().forEach { sources.addLast(it) }
        return env
    }

    @Test
    fun `base application yml has no hardcoded JWT secret default`() {
        val raw = ClassPathResource("application.yml").inputStream
            .bufferedReader().readText()
        // The placeholder MUST be a bare ${JWT_SECRET} with no `:fallback`.
        // If someone reintroduces a default we fail loudly.
        val jwtLine = raw.lines().first { it.contains("\${JWT_SECRET") }
        assertTrue(
            jwtLine.contains("\${JWT_SECRET}"),
            "application.yml must have bare \${JWT_SECRET} with no default. Got: $jwtLine",
        )
        assertFalse(
            jwtLine.contains("\${JWT_SECRET:"),
            "application.yml must NOT supply a default for JWT_SECRET. Got: $jwtLine",
        )
    }

    @Test
    fun `base application yml has no hardcoded Google redirect default`() {
        val raw = ClassPathResource("application.yml").inputStream
            .bufferedReader().readText()
        val redirectLine = raw.lines().first { it.contains("\${GOOGLE_REDIRECT_URI") }
        assertTrue(
            redirectLine.contains("\${GOOGLE_REDIRECT_URI}"),
            "application.yml must have bare \${GOOGLE_REDIRECT_URI}. Got: $redirectLine",
        )
        assertFalse(
            redirectLine.contains("\${GOOGLE_REDIRECT_URI:"),
            "application.yml must NOT supply a default for GOOGLE_REDIRECT_URI. Got: $redirectLine",
        )
    }

    @Test
    fun `missing JWT_SECRET makes placeholder resolution fail`() {
        // No JWT_SECRET in the env — Spring's placeholder resolver must raise.
        val env = envWith(mapOf("GOOGLE_REDIRECT_URI" to "https://example.com/cb"))
        val ex = assertThrows(IllegalArgumentException::class.java) {
            env.resolveRequiredPlaceholders("\${app.jwt.secret}")
        }
        assertTrue(
            ex.message!!.contains("JWT_SECRET"),
            "Expected resolver error to mention JWT_SECRET. Got: ${ex.message}",
        )
        println("[V2] fail-fast error: ${ex.message}")
    }

    @Test
    fun `GOOGLE_REDIRECT_URI env var threads through to app oauth google redirect-uri`() {
        val customUri = "https://werewolf.example.com/auth/callback/google"
        val env = envWith(
            mapOf(
                "JWT_SECRET" to "x".repeat(48),
                "GOOGLE_REDIRECT_URI" to customUri,
            ),
        )
        val resolved = env.resolveRequiredPlaceholders("\${app.oauth.google.redirect-uri}")
        assertEquals(customUri, resolved)
        assertFalse(
            resolved.contains("localhost"),
            "Resolved redirect-uri must not contain 'localhost' when env is set. Got: $resolved",
        )
        println("[V3] resolved redirect-uri: $resolved")
    }

    @Test
    fun `missing GOOGLE_REDIRECT_URI also fails placeholder resolution in base profile`() {
        val env = envWith(mapOf("JWT_SECRET" to "x".repeat(48)))
        val ex = assertThrows(IllegalArgumentException::class.java) {
            env.resolveRequiredPlaceholders("\${app.oauth.google.redirect-uri}")
        }
        assertTrue(
            ex.message!!.contains("GOOGLE_REDIRECT_URI"),
            "Expected resolver error to mention GOOGLE_REDIRECT_URI. Got: ${ex.message}",
        )
    }
}
