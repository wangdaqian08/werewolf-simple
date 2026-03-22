package com.werewolf.integration

object TestConstants {
    // URLs
    const val LOGIN_URL = "/api/user/login"
    const val CREATE_ROOM_URL = "/api/room/create"
    const val JOIN_ROOM_URL = "/api/room/join"

    // User fields
    const val FIELD_NICKNAME = "nickname"
    const val FIELD_TOKEN = "token"
    const val FIELD_USER = "user"
    const val FIELD_USER_ID = "userId"

    // Room fields
    const val FIELD_CONFIG = "config"
    const val FIELD_TOTAL_PLAYERS = "totalPlayers"
    const val FIELD_ROLES = "roles"
    const val FIELD_ROOM_ID = "roomId"
    const val FIELD_ROOM_CODE = "roomCode"
    const val FIELD_STATUS = "status"
    const val FIELD_PLAYERS = "players"
    const val FIELD_ERROR = "error"

    // Room values
    const val ROOM_CODE_LENGTH = 4
    const val DEFAULT_TOTAL_PLAYERS = 6
    const val INVALID_ROOM_CODE = "ZZZZ"
}
