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
    const val ROOM_CODE_LENGTH = 3
    const val DEFAULT_TOTAL_PLAYERS = 6
    const val INVALID_ROOM_CODE = "ZZZ"

    // Room-code sequence for tests that seed Room rows directly (bypassing the
    // API's generateCode). JVM-global so every class sharing the schema draws
    // from ONE sequence — per-class copies emit identical codes, and duplicate
    // codes break findActiveByRoomCode's at-most-one invariant, 500-ing
    // unrelated createRoom calls. 400-999 avoids RoomControllerTest's fixed
    // 111/222/333.
    private val SEEDED_ROOM_CODE_SEQ = java.util.concurrent.atomic.AtomicInteger(0)
    fun nextSeededRoomCode(): String = (400 + SEEDED_ROOM_CODE_SEQ.getAndIncrement() % 600).toString()
}
