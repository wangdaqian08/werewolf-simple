package com.werewolf.config

import com.werewolf.util.JwtUtil
import io.jsonwebtoken.JwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class JwtFilter(private val jwtUtil: JwtUtil) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val token = extractToken(request)
        if (token != null) {
            try {
                val claims = jwtUtil.parseToken(token)
                val userId = claims.subject
                if (!userId.isNullOrBlank() && SecurityContextHolder.getContext().authentication == null) {
                    SecurityContextHolder.getContext().authentication =
                        UsernamePasswordAuthenticationToken(userId, null, emptyList())
                }
            } catch (e: JwtException) {
                logger.debug("Invalid JWT token: ${e.message}")
            } catch (e: IllegalArgumentException) {
                logger.debug("Invalid JWT token: ${e.message}")
            }
        }
        chain.doFilter(request, response)
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.servletPath.startsWith("/api/auth/")

    private fun extractToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        return if (header.startsWith("Bearer ")) header.substring(7) else null
    }
}
