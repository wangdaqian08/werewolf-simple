package com.werewolf.controller

import com.werewolf.auth.AuthService
import com.werewolf.dto.UserLoginRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.text.Normalizer

/**
 * Guest login — no OAuth required.
 *
 * POST /api/user/login  { "nickname": "Alice" }
 *
 * The userId is derived deterministically from the nickname — `guest:alice` for
 * any "Alice" / "alice" / "  ALICE  ". Logging in again with the same nickname
 * returns the SAME userId, so a player who lost their JWT (new browser, cleared
 * cache, dropped connection) can rejoin a room/game by simply re-entering their
 * nickname. Look-alike input maps to the same id too (see [guestUserId]), so a
 * different keyboard can't turn a returning player into a stranger.
 * Trade-off: anyone who knows your nickname can become you. Acceptable for the
 * small-group, host-vetted-lobby use case.
 *
 * Returns a JWT the client uses for all subsequent authenticated requests.
 */
@RestController
@RequestMapping("/api/user")
class UserController(private val authService: AuthService) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: UserLoginRequest): ResponseEntity<Any> {
        val nickname = displayNickname(request.nickname)
        if (nickname.isEmpty())
            return ResponseEntity.badRequest().body(mapOf("error" to "Nickname must contain visible characters"))
        val userId = guestUserId(nickname)
        // NFKC can expand a char up to 18x; users.user_id is VARCHAR(128).
        if (userId.length > 128)
            return ResponseEntity.badRequest().body(mapOf("error" to "Nickname is too long"))
        return ResponseEntity.ok(authService.loginOrRegister(userId, nickname, null))
    }
}

// Control/format chars except whitespace (tab etc. is folded below, not deleted).
// ZWJ/ZWNJ are spared for display: they build emoji like 👨‍👩‍👧 and Persian/Indic words.
private val INVISIBLE_FOR_DISPLAY = Regex("[\\p{Cc}\\p{Cf}&&[^\\s\\u200C\\u200D]]")
private val STRAY_JOINER = Regex("(?<![^\\x00-\\x7F])[\\u200C\\u200D]|[\\u200C\\u200D](?![^\\x00-\\x7F])")
// The id also drops joiners and variation selectors, so ❤ and ❤️ are one player.
private val INVISIBLE_FOR_ID = Regex("[\\p{Cc}\\p{Cf}\\uFE0E\\uFE0F&&[^\\s]]")
private val WHITESPACE_RUN = Regex("[\\s\\p{Z}]+")

/** What other players see: the typed nickname minus invisible junk, single-spaced. */
internal fun displayNickname(raw: String): String =
    raw.replace(INVISIBLE_FOR_DISPLAY, "").replace(STRAY_JOINER, "").replace(WHITESPACE_RUN, " ").trim()

/**
 * NFKC folds full-width forms (Ｆ！ → F!), odd spaces (U+3000, U+2006, NBSP → space)
 * and composed/decomposed accents into one spelling; CJK and emoji are unchanged.
 */
internal fun guestUserId(nickname: String): String {
    val key = Normalizer.normalize(nickname, Normalizer.Form.NFKC)
        .replace(INVISIBLE_FOR_ID, "")
        .trim()
        .lowercase()
        .replace(WHITESPACE_RUN, "-")
    return "guest:$key"
}
