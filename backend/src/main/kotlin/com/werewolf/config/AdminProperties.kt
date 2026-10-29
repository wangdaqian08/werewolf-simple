package com.werewolf.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Admin credit grants (AdminService). The 4-digit PIN comes from the ADMIN_PIN
 * env var; when it is unset, grants are refused (503) rather than accepting a
 * default PIN that would be public in git.
 */
@ConfigurationProperties(prefix = "werewolf.admin")
data class AdminProperties(
    val pin: String = "",
)
