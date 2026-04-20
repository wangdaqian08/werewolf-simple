package com.werewolf.config

import com.werewolf.util.JwtUtil
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtUtil: JwtUtil,
    @Value("\${app.cors.allowed-origins}") private val corsAllowedOrigins: String,
) {

    @Bean
    fun jwtFilter(): JwtFilter = JwtFilter(jwtUtil)

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val origins = corsAllowedOrigins.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val config = CorsConfiguration().apply {
            // JWT is sent in Authorization header (see JwtFilter); no cookies/session used.
            // Keeping allowCredentials=false lets a wildcard origin work in dev without
            // Spring rejecting the combination as illegal per the CORS spec.
            allowedOriginPatterns = origins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "X-Requested-With")
            allowCredentials = false
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/api/user/login").permitAll()
                    .requestMatchers("/api/health").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/ws/**").permitAll()
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().permitAll()
            }
            .addFilterBefore(jwtFilter(), UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }
}
