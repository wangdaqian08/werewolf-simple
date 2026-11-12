package com.werewolf.model

/** 单局任务: dealt after the roles, judged at settlement, paid in 戰績分 only. */
enum class GameTask(val score: Int) {
    SURVIVE_TO_DAY3(5),
    HOLD_BADGE(10),
    VOTE_WOLF_TWICE(10),
    VILLAGER_SURVIVE_WIN(10),
    SEER_FIND_TWO_WOLVES(10),
    WITCH_POISON_WOLF(10),
    WITCH_SAVE_GOOD(5),
    GUARD_BLOCK_KILL(10),
    WOLF_NEVER_EXILED(10),
    WOLF_WIN_ALIVE(20),
    WOLF_FIRST_NIGHT_GOD(10),
}
