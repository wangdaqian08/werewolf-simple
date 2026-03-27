package com.werewolf.model

enum class RoomStatus { WAITING, IN_GAME, CLOSED }

enum class ReadyStatus { NOT_READY, READY }

enum class GamePhase { ROLE_REVEAL, SHERIFF_ELECTION, DAY, VOTING, NIGHT, GAME_OVER }

enum class PlayerRole { WEREWOLF, VILLAGER, SEER, WITCH, HUNTER, GUARD, IDIOT }

enum class NightSubPhase { WAITING, WEREWOLF_PICK, SEER_PICK, SEER_RESULT, WITCH_ACT, GUARD_PICK, COMPLETE }

enum class ElectionSubPhase { SIGNUP, SPEECH, VOTING, RESULT }

enum class CandidateStatus { RUNNING, QUIT }

enum class VoteContext { SHERIFF_ELECTION, ELIMINATION }

enum class WinnerSide { WEREWOLF, VILLAGER }

enum class DaySubPhase { RESULT_HIDDEN, RESULT_REVEALED }

enum class VotingSubPhase { VOTING, VOTE_RESULT, HUNTER_SHOOT, BADGE_HANDOVER }

enum class ActionType {
    // Role reveal
    CONFIRM_ROLE, START_NIGHT,

    // Day phase
    REVEAL_NIGHT_RESULT, DAY_ADVANCE,

    // Voting
    SUBMIT_VOTE, VOTING_REVEAL_TALLY, VOTING_CONTINUE,

    // Hunter + badge (post-elimination)
    HUNTER_SHOOT, HUNTER_SKIP, BADGE_PASS, BADGE_DESTROY,

    // Night: werewolf
    WOLF_KILL,

    // Night: seer
    SEER_CHECK, SEER_CONFIRM,

    // Night: witch
    WITCH_ACT,

    // Night: guard
    GUARD_PROTECT, GUARD_SKIP,

    // Sheriff election
    SHERIFF_CAMPAIGN, SHERIFF_QUIT, SHERIFF_START_SPEECH, SHERIFF_ADVANCE_SPEECH, SHERIFF_REVEAL_RESULT,
    SHERIFF_PASS, SHERIFF_QUIT_CAMPAIGN, SHERIFF_VOTE, SHERIFF_CONFIRM_VOTE, SHERIFF_ABSTAIN,
}
