package com.werewolf.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

/**
 * WeChat OAuth2 service for Official Account (网页授权) login.
 *
 * TODO: Requires WECHAT_APP_ID and WECHAT_APP_SECRET env vars.
 *       For WeChat Mini-Program, use wx.login() → code → jscode2session endpoint instead.
 */
@Service
class WeChatOAuthService(
    @Value("\${app.oauth.wechat.app-id:}") private val appId: String,
    @Value("\${app.oauth.wechat.app-secret:}") private val appSecret: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restTemplate = RestTemplate()

    companion object {
        private const val ACCESS_TOKEN_URI = "https://api.weixin.qq.com/sns/oauth2/access_token"
        private const val USER_INFO_URI = "https://api.weixin.qq.com/sns/userinfo"
    }

    data class WeChatUserProfile(val userId: String, val nickname: String, val avatarUrl: String?)

    fun exchangeCode(code: String): WeChatUserProfile {
        log.debug("Exchanging WeChat OAuth code for tokens")
        val tokenResponse = fetchAccessToken(code)
        val openId =
            tokenResponse["openid"] as? String ?: error("Missing openid in WeChat token response: $tokenResponse")
        val accessToken = tokenResponse["access_token"] as? String
            ?: error("Missing access_token in WeChat token response: $tokenResponse")
        return fetchUserInfo(accessToken, openId)
    }

    @Suppress("UNCHECKED_CAST")
    private fun fetchAccessToken(code: String): Map<String, Any> {
        val url = UriComponentsBuilder.fromHttpUrl(ACCESS_TOKEN_URI)
            .queryParam("appid", appId)
            .queryParam("secret", appSecret)
            .queryParam("code", code)
            .queryParam("grant_type", "authorization_code")
            .toUriString()
        val response = restTemplate.getForObject(url, Map::class.java) as? Map<String, Any>
            ?: error("Null response from WeChat token endpoint")
        if (response.containsKey("errcode")) {
            error("WeChat token error: ${response["errmsg"]} (code=${response["errcode"]})")
        }
        return response
    }

    @Suppress("UNCHECKED_CAST")
    private fun fetchUserInfo(accessToken: String, openId: String): WeChatUserProfile {
        val url = UriComponentsBuilder.fromHttpUrl(USER_INFO_URI)
            .queryParam("access_token", accessToken)
            .queryParam("openid", openId)
            .queryParam("lang", "zh_CN")
            .toUriString()
        val response = restTemplate.getForObject(url, Map::class.java) as? Map<String, Any>
            ?: error("Null response from WeChat userinfo endpoint")
        if (response.containsKey("errcode")) {
            error("WeChat userinfo error: ${response["errmsg"]} (code=${response["errcode"]})")
        }
        val nickname = response.getOrDefault("nickname", "微信用户") as String
        val headImgUrl = response["headimgurl"] as? String
        return WeChatUserProfile("wechat:$openId", nickname, headImgUrl)
    }
}
