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

    private companion object {
        /** Result-display sub-phases: no open-eyes audio, and entering one plays no close-eyes audio. */
        val RESULT_SUB_PHASES = setOf(NightSubPhase.SEER_RESULT, NightSubPhase.MECH_WOLF_RESULT, NightSubPhase.MEDIUM_RESULT)

        /** Each PICK sub-phase paired with the RESULT sub-phase that closes the same role's turn. */
        val PICK_TO_RESULT = mapOf(
            NightSubPhase.SEER_PICK to NightSubPhase.SEER_RESULT,
            NightSubPhase.MECH_WOLF_PICK to NightSubPhase.MECH_WOLF_RESULT,
            NightSubPhase.MEDIUM_PICK to NightSubPhase.MEDIUM_RESULT,
        )
    }

    /**
     * Map NightSubPhase to PlayerRole
     */
    private fun mapSubPhaseToRole(subPhase: NightSubPhase): PlayerRole? {
        return when (subPhase) {
            NightSubPhase.WEREWOLF_PICK -> PlayerRole.WEREWOLF
            NightSubPhase.SEER_PICK -> PlayerRole.SEER
            NightSubPhase.SEER_RESULT -> PlayerRole.SEER
            NightSubPhase.MECH_WOLF_PICK, NightSubPhase.MECH_WOLF_RESULT -> PlayerRole.MECHANICAL_WOLF
            NightSubPhase.MEDIUM_PICK, NightSubPhase.MEDIUM_RESULT -> PlayerRole.MEDIUM
            NightSubPhase.GRAVEKEEPER_RESULT -> PlayerRole.GRAVEKEEPER
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
        oldSubPhase: String?,
        newSubPhase: String?,
        room: Room,
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
                // A RESULT sub-phase is a result display phase, not a role phase:
                // don't add close eyes audio when entering one.
                if (newSubPhase !in RESULT_SUB_PHASES) {
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
        // RESULT sub-phases have no open eyes audio (they just show results)
        if (subPhase in RESULT_SUB_PHASES) return null

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

        try {
            when (phase) {
                GamePhase.NIGHT -> {
                    // NIGHT phase: check if we're entering night (goes_dark_close_eyes) or in a sub-phase
                    if (nightSubPhase != null) {
                        val subPhaseEnum = runCatching { NightSubPhase.valueOf(nightSubPhase) }.onFailure { log.error("Invalid night sub-phase in game state: $nightSubPhase", it) }.getOrNull()
                        if (subPhaseEnum != null) {
                            // In a sub-phase, no audio (audio was played during transition)
                            // But we include an empty sequence for consistency
                        }
                    } else {
                        // Just entered NIGHT phase
                        audioFiles.add("goes_dark_close_eyes.mp3")
                        audioFiles.add("wolf_howl.mp3")
                    }
                }

                GamePhase.DAY_DISCUSSION -> {
                    // DAY phase: day_time.mp3 was played during transition
                    // Empty sequence for current state
                }

                GamePhase.ROLE_REVEAL, GamePhase.SHERIFF_ELECTION, GamePhase.DAY_VOTING, GamePhase.GAME_OVER, GamePhase.WAITING, GamePhase.DAY_PENDING -> {
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
        } catch (e: Exception) {
            log.error("Error calculating game state audio for game $gameId: ${e.message}", e)
            return AudioSequence(
                id = "${gameId}-${System.currentTimeMillis()}-STATE-${phase.name}-ERROR",
                phase = phase,
                subPhase = subPhase ?: nightSubPhase,
                audioFiles = emptyList(),
                priority = 0,
            )
        }
    }

    /**
     * Calculate audio sequence for dead role transitions
     * For dead roles, we play the complete sequence: open eyes → close eyes
     * to simulate player operation time.
     */
    fun calculateDeadRoleAudioSequence(
        gameId: Int,
        skippedRoles: List<NightSubPhase>,
        targetSubPhase: NightSubPhase,
    ): AudioSequence {
        val audioFiles = mutableListOf<String>()

        try {
            // Process all skipped roles in order
            for (skippedRole in skippedRoles) {
                if (skippedRole == NightSubPhase.WAITING) continue
                
                // PICK/RESULT pairs (seer, mech wolf, medium) form one open → close sequence
                val resultPhase = PICK_TO_RESULT[skippedRole]
                if (resultPhase != null) {
                    if (skippedRoles.contains(resultPhase)) {
                        // Role completely dead: play open_eyes → close_eyes once; RESULT is consumed here
                        getOpenEyesAudio(skippedRole)?.let { audioFiles.add(it) }
                        getCloseEyesAudio(resultPhase)?.let { audioFiles.add(it) }
                    } else {
                        // Only PICK skipped (unusual case)
                        getOpenEyesAudio(skippedRole)?.let { audioFiles.add(it) }
                    }
                } else if (skippedRole in RESULT_SUB_PHASES) {
                    // RESULT whose PICK was skipped was already handled with its PICK above
                    if (PICK_TO_RESULT.any { (pick, result) -> result == skippedRole && skippedRoles.contains(pick) }) continue
                    // Only RESULT skipped (unusual case)
                    getCloseEyesAudio(skippedRole)?.let { audioFiles.add(it) }
                } else {
                    // Normal role: complete sequence open eyes → close eyes
                    val openEyesAudio = getOpenEyesAudio(skippedRole)
                    val closeEyesAudio = getCloseEyesAudio(skippedRole)
                    if (openEyesAudio != null) audioFiles.add(openEyesAudio)
                    if (closeEyesAudio != null) audioFiles.add(closeEyesAudio)
                }
            }

            // Add "open eyes" audio for the target (alive) role
            if (targetSubPhase != NightSubPhase.WAITING && targetSubPhase != NightSubPhase.COMPLETE) {
                val openEyesAudio = getOpenEyesAudio(targetSubPhase)
                if (openEyesAudio != null) {
                    audioFiles.add(openEyesAudio)
                }
            }

            log.info("[AudioService] game=$gameId: calculated dead-role audio sequence: [${audioFiles.joinToString(", ")}]")

            return AudioSequence(
                id = "${gameId}-${System.currentTimeMillis()}-DEAD-ROLE-${targetSubPhase.name}",
                phase = GamePhase.NIGHT,
                subPhase = targetSubPhase.name,
                audioFiles = audioFiles,
                priority = 3, // Dead role audio has lower priority than normal transitions
            )
        } catch (e: Exception) {
            log.error("Error calculating dead role audio sequence for game $gameId: ${e.message}", e)
            return AudioSequence(
                id = "${gameId}-${System.currentTimeMillis()}-DEAD-ROLE-${targetSubPhase.name}-ERROR",
                phase = GamePhase.NIGHT,
                subPhase = targetSubPhase.name,
                audioFiles = emptyList(),
                priority = 0,
            )
        }
    }
}