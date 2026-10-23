package com.werewolf.service

import com.werewolf.audio.RoleRegistry
import com.werewolf.model.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Audio Service - Calculates audio sequences for game phases
 * Backend determines what audio should play, frontend just plays it
 */
@Service
class AudioService {

    private val log = LoggerFactory.getLogger(AudioService::class.java)

    /**
     * Map NightSubPhase to PlayerRole
     */
    private fun mapSubPhaseToRole(subPhase: NightSubPhase): PlayerRole? {
        return when (subPhase) {
            NightSubPhase.WEREWOLF_PICK -> PlayerRole.WEREWOLF
            NightSubPhase.SEER_PICK -> PlayerRole.SEER
            NightSubPhase.SEER_RESULT -> PlayerRole.SEER
            NightSubPhase.WITCH_ACT -> PlayerRole.WITCH
            NightSubPhase.GUARD_PICK -> PlayerRole.GUARD
            else -> null
        }
    }

    /**
     * Calculate audio sequence for phase transition
     */
    fun calculatePhaseTransition(
        gameId: Int,
        oldPhase: GamePhase?,
        newPhase: GamePhase,
        newSubPhase: String?,
    ): AudioSequence {
        val audioFiles = mutableListOf<String>()

        try {
            when (newPhase) {
                GamePhase.NIGHT -> {
                    // Night entry audio: darkness + wolf howl. The first role's open-eyes
                    // audio (wolf_open_eyes.mp3) is broadcast separately when the night
                    // coroutine's role loop reaches WEREWOLF_PICK, after
                    // NIGHT_INIT_AUDIO_DELAY_MS has elapsed client-side — that delay
                    // guarantees goes_dark_close_eyes + wolf_howl have finished playing
                    // before the next sequence arrives (which would clear the client
                    // audio queue per useAudioService.ts).
                    audioFiles.add("goes_dark_close_eyes.mp3")
                    audioFiles.add("wolf_howl.mp3")
                }

                GamePhase.DAY_DISCUSSION -> {
                    // Entering day phase: rooster + day_time signals dawn / "open eyes".
                    // Suppress on the Day-1 sheriff path (SHERIFF_ELECTION → DAY_DISCUSSION) —
                    // the cue was already played when SHERIFF_ELECTION opened, otherwise
                    // players would hear it twice on Day 1.
                    if (oldPhase != GamePhase.SHERIFF_ELECTION) {
                        audioFiles.add("rooster_crowing.mp3")
                        audioFiles.add("day_time.mp3")
                    }
                }

                GamePhase.SHERIFF_ELECTION -> {
                    // Variant B Day 1: NIGHT → SHERIFF_ELECTION carries the morning cue.
                    // Players need an "open eyes" signal after GUARD's close-eyes audio;
                    // without this they don't know when the campaign starts. The matching
                    // DAY_DISCUSSION → no-op above prevents a duplicate at host reveal.
                    if (oldPhase == GamePhase.NIGHT) {
                        audioFiles.add("rooster_crowing.mp3")
                        audioFiles.add("day_time.mp3")
                    }
                }

                GamePhase.ROLE_REVEAL, GamePhase.DAY_VOTING, GamePhase.GAME_OVER, GamePhase.WAITING, GamePhase.DAY_PENDING -> {
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
        } catch (e: Exception) {
            log.error("Error calculating phase transition audio for game $gameId: ${e.message}", e)
            // Return empty audio sequence on error
            return AudioSequence(
                id = "${gameId}-${System.currentTimeMillis()}-${newPhase.name}-ERROR",
                phase = newPhase,
                subPhase = newSubPhase,
                audioFiles = emptyList(),
                priority = 0,
            )
        }
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

        try {
            // Add "close eyes" audio for previous role
            if (oldSubPhase != null && oldSubPhase != NightSubPhase.WAITING) {
                // SEER_RESULT is a result display phase, not a role phase
                // Don't add close eyes audio when entering SEER_RESULT
                if (newSubPhase != NightSubPhase.SEER_RESULT) {
                    getCloseEyesAudio(oldSubPhase)?.let { audioFiles.add(it) }
                }
            }

            // Add "open eyes" audio for new role
            getOpenEyesAudio(newSubPhase)?.let { audioFiles.add(it) }

            return AudioSequence(
                id = "${gameId}-${System.currentTimeMillis()}-NIGHT-${newSubPhase.name}",
                phase = GamePhase.NIGHT,
                subPhase = newSubPhase.name,
                audioFiles = audioFiles,
                priority = 5, // Sub-phase transitions have lower priority than phase transitions
            )
        } catch (e: Exception) {
            log.error("Error calculating night sub-phase transition audio for game $gameId: ${e.message}", e)
            return AudioSequence(
                id = "${gameId}-${System.currentTimeMillis()}-NIGHT-${newSubPhase.name}-ERROR",
                phase = GamePhase.NIGHT,
                subPhase = newSubPhase.name,
                audioFiles = emptyList(),
                priority = 0,
            )
        }
    }

    /**
     * Calculate "close eyes" audio for a role using RoleRegistry
     * Public for testing purposes.
     */
    fun calculateCloseEyesAudio(subPhase: NightSubPhase): String? {
        val role = mapSubPhaseToRole(subPhase) ?: return null
        return RoleRegistry.getCloseEyesAudio(role)
    }

    /**
     * Calculate "open eyes" audio for a role using RoleRegistry
     * Public for testing purposes.
     */
    fun calculateOpenEyesAudio(subPhase: NightSubPhase): String? {
        // SEER_RESULT doesn't have open eyes audio (it's just showing results)
        if (subPhase == NightSubPhase.SEER_RESULT) return null

        val role = mapSubPhaseToRole(subPhase) ?: return null
        return RoleRegistry.getOpenEyesAudio(role)
    }

    /**
     * Get "close eyes" audio for a role using RoleRegistry (internal use)
     */
    private fun getCloseEyesAudio(subPhase: NightSubPhase): String? = calculateCloseEyesAudio(subPhase)

    /**
     * Get "open eyes" audio for a role using RoleRegistry (internal use)
     */
    private fun getOpenEyesAudio(subPhase: NightSubPhase): String? = calculateOpenEyesAudio(subPhase)
}
