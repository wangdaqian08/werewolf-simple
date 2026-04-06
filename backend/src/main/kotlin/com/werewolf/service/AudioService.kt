package com.werewolf.service

import com.werewolf.model.*
import com.werewolf.repository.NightPhaseRepository
import org.springframework.stereotype.Service

/**
 * Audio Service - Calculates audio sequences for game phases
 * Backend determines what audio should play, frontend just plays it
 */
@Service
class AudioService(
    private val nightPhaseRepository: NightPhaseRepository,
) {

    /**
     * Calculate audio sequence for phase transition
     */
    fun calculatePhaseTransition(
        gameId: Int,
        oldPhase: GamePhase?,
        newPhase: GamePhase,
        oldSubPhase: String?,
        newSubPhase: String?,
        room: Room,
    ): AudioSequence {
        val audioFiles = mutableListOf<String>()

        when (newPhase) {
            GamePhase.NIGHT -> {
                // Entering night phase
                audioFiles.add("天黑请闭眼.mp3")

                // If transitioning to a specific sub-phase immediately, add role audio
                if (newSubPhase != null) {
                    val subPhaseEnum = try {
                        NightSubPhase.valueOf(newSubPhase)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                    if (subPhaseEnum != null && subPhaseEnum != NightSubPhase.WAITING) {
                        val roleAudio = getOpenEyesAudio(subPhaseEnum)
                        if (roleAudio != null) {
                            audioFiles.add(roleAudio)
                        }
                    }
                }
            }

            GamePhase.DAY -> {
                // Entering day phase
                audioFiles.add("天亮了.mp3")
            }

            GamePhase.ROLE_REVEAL, GamePhase.SHERIFF_ELECTION, GamePhase.VOTING, GamePhase.GAME_OVER -> {
                // No audio for these phases
            }
        }

        // Determine priority based on whether this phase has audio
        val priority = if (audioFiles.isEmpty()) 0 else 10

        return AudioSequence(
            id = "${gameId}-${System.currentTimeMillis()}-${newPhase.name}",
            phase = newPhase,
            subPhase = newSubPhase,
            audioFiles = audioFiles,
            priority = priority,
        )
    }

    /**
     * Calculate audio sequence for night sub-phase transition
     */
    fun calculateNightSubPhaseTransition(
        gameId: Int,
        oldSubPhase: NightSubPhase?,
        newSubPhase: NightSubPhase,
    ): AudioSequence {
        val audioFiles = mutableListOf<String>()

        // Add "close eyes" audio for previous role
        if (oldSubPhase != null && oldSubPhase != NightSubPhase.WAITING) {
            val closeEyesAudio = getCloseEyesAudio(oldSubPhase)
            if (closeEyesAudio != null) {
                audioFiles.add(closeEyesAudio)
            }
        }

        // Add "open eyes" audio for new role
        val openEyesAudio = getOpenEyesAudio(newSubPhase)
        if (openEyesAudio != null) {
            audioFiles.add(openEyesAudio)
        }

        return AudioSequence(
            id = "${gameId}-${System.currentTimeMillis()}-NIGHT-${newSubPhase.name}",
            phase = GamePhase.NIGHT,
            subPhase = newSubPhase.name,
            audioFiles = audioFiles,
            priority = 5, // Sub-phase transitions have lower priority than phase transitions
        )
    }

    /**
     * Get "close eyes" audio for a role
     */
    private fun getCloseEyesAudio(subPhase: NightSubPhase): String? {
        return when (subPhase) {
            NightSubPhase.WEREWOLF_PICK -> "狼人请闭眼.mp3"
            NightSubPhase.SEER_PICK -> "预言家请闭眼.mp3"
            NightSubPhase.SEER_RESULT -> "预言家请闭眼.mp3"
            NightSubPhase.WITCH_ACT -> "女巫请闭眼.mp3"
            NightSubPhase.GUARD_PICK -> "守卫请闭眼.mp3"
            else -> null
        }
    }

    /**
     * Get "open eyes" audio for a role
     */
    private fun getOpenEyesAudio(subPhase: NightSubPhase): String? {
        return when (subPhase) {
            NightSubPhase.WEREWOLF_PICK -> "狼人请睁眼.mp3"
            NightSubPhase.SEER_PICK -> "预言家请睁眼.mp3"
            NightSubPhase.WITCH_ACT -> "女巫请睁眼.mp3"
            NightSubPhase.GUARD_PICK -> "守卫请睁眼.mp3"
            else -> null
        }
    }

    /**
     * Calculate audio sequence for current game state
     * Used by GameService to include audio sequence in GameState response
     */
    fun calculateGameStateAudio(
        gameId: Int,
        phase: GamePhase,
        subPhase: String?,
        nightSubPhase: String?,
    ): AudioSequence {
        val audioFiles = mutableListOf<String>()

        when (phase) {
            GamePhase.NIGHT -> {
                // NIGHT phase: check if we're entering night (天黑请闭眼) or in a sub-phase
                if (nightSubPhase != null) {
                    val subPhaseEnum = try {
                        NightSubPhase.valueOf(nightSubPhase)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                    if (subPhaseEnum != null) {
                        // In a sub-phase, no audio (audio was played during transition)
                        // But we include an empty sequence for consistency
                    }
                } else {
                    // Just entered NIGHT phase
                    audioFiles.add("天黑请闭眼.mp3")
                }
            }

            GamePhase.DAY -> {
                // DAY phase: 天亮了.mp3 was played during transition
                // Empty sequence for current state
            }

            GamePhase.ROLE_REVEAL, GamePhase.SHERIFF_ELECTION, GamePhase.VOTING, GamePhase.GAME_OVER -> {
                // No audio for these phases
            }
        }

        return AudioSequence(
            id = "${gameId}-${System.currentTimeMillis()}-STATE-${phase.name}",
            phase = phase,
            subPhase = subPhase ?: nightSubPhase,
            audioFiles = audioFiles,
            priority = 0, // State audio has lowest priority
        )
    }
}