package com.werewolf.config

import com.werewolf.auth.UserClaims
import com.werewolf.util.JwtUtil
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
                    val nickname = claims.get("nickname", String::class.java) ?: userId
                    val avatarUrl = claims.get("avatarUrl", String::class.java)
                    val auth = UsernamePasswordAuthenticationToken(userId, null, emptyList())
                    auth.details = UserClaims(userId, nickname, avatarUrl)
                    SecurityContextHolder.getContext().authentication = auth
                }
            } catch (e: Exception) {
                handleTokenException(e, response)
                return
            }
        }
        chain.doFilter(request, response)
    }

    private fun handleTokenException(e: Exception, response: HttpServletResponse) {
        logger.debug("Invalid JWT token: ${e.message}")
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token")
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.servletPath.startsWith("/api/auth/") ||
        request.servletPath == "/api/user/login" ||
        request.servletPath == "/api/health"

    private fun extractToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        return if (header.startsWith("Bearer ")) header.substring(7) else null
    }
}
