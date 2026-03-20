package com.werewolf.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate

@Service
class GoogleOAuthService(
    @Value("\${app.oauth.google.client-id:}") private val clientId: String,
    @Value("\${app.oauth.google.client-secret:}") private val clientSecret: String,
    @Value("\${app.oauth.google.token-uri:https://oauth2.googleapis.com/token}") private val tokenUri: String,
    @Value("\${app.oauth.google.userinfo-uri:https://www.googleapis.com/oauth2/v3/userinfo}") private val userInfoUri: String,
    @Value("\${app.oauth.google.redirect-uri:http://localhost:5173/auth/callback/google}") private val redirectUri: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restTemplate = RestTemplate()

    data class GoogleUserProfile(val userId: String, val nickname: String, val avatarUrl: String?)

    fun exchangeCode(code: String): GoogleUserProfile {
        log.debug("Exchanging Google OAuth code for tokens")
        val accessToken = fetchAccessToken(code)
        return fetchUserProfile(accessToken)
    }

    @Suppress("UNCHECKED_CAST")
    private fun fetchAccessToken(code: String): String {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
        val params = LinkedMultiValueMap<String, String>().apply {
            add("code", code)
            add("client_id", clientId)
            add("client_secret", clientSecret)
            add("redirect_uri", redirectUri)
            add("grant_type", "authorization_code")
        }
        val response = restTemplate.exchange(tokenUri, HttpMethod.POST, HttpEntity(params, headers), Map::class.java)
        val body = response.body as? Map<String, Any>
            ?: error("Failed to obtain access_token from Google: null response")
        return body["access_token"] as? String
            ?: error("Failed to obtain access_token from Google: $body")
    }

    @Suppress("UNCHECKED_CAST")
    private fun fetchUserProfile(accessToken: String): GoogleUserProfile {
        val headers = HttpHeaders().apply { setBearerAuth(accessToken) }
        val response = restTemplate.exchange(userInfoUri, HttpMethod.GET, HttpEntity<Void>(headers), Map::class.java)
        val body = response.body as? Map<String, Any>
            ?: error("Failed to obtain user info from Google: null response")
        val sub = body["sub"] as? String ?: error("Missing 'sub' in Google userinfo: $body")
        val name = body.getOrDefault("name", "Google User") as String
        val picture = body["picture"] as? String
        return GoogleUserProfile("google:$sub", name, picture)
    }
}
