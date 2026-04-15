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
        oldSubPhase: String?,
        newSubPhase: String?,
        room: Room,
    ): AudioSequence {
        val audioFiles = mutableListOf<String>()

        try {
            when (newPhase) {
                GamePhase.NIGHT -> {
                    // Entering night phase
                    audioFiles.add("goes_dark_close_eyes.mp3")
                    audioFiles.add("wolf_howl.mp3")

                    // If transitioning to a specific sub-phase immediately, add role audio
                    if (newSubPhase != null) {
                        val subPhaseEnum = runCatching { NightSubPhase.valueOf(newSubPhase) }.onFailure { log.error("Invalid night sub-phase: $newSubPhase", it) }.getOrNull()
                        if (subPhaseEnum != null && subPhaseEnum != NightSubPhase.WAITING) {
                            getOpenEyesAudio(subPhaseEnum)?.let { audioFiles.add(it) }
                        }
                    }
                }

                GamePhase.DAY_DISCUSSION -> {
                    // Entering day phase
                    audioFiles.add("day_time.mp3")
                    audioFiles.add("rooster_crowing.mp3")
                }

                GamePhase.ROLE_REVEAL, GamePhase.SHERIFF_ELECTION, GamePhase.DAY_VOTING, GamePhase.GAME_OVER, GamePhase.WAITING, GamePhase.DAY_PENDING -> {
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
                
                // Special handling for seer: SEER_PICK and SEER_RESULT together form one complete sequence
                if (skippedRole == NightSubPhase.SEER_PICK) {
                    // Check if SEER_RESULT is also skipped (seer is completely dead)
                    val seerResultSkipped = skippedRoles.contains(NightSubPhase.SEER_RESULT)
                    if (seerResultSkipped) {
                        // Dead seer: play seer_open_eyes.mp3 → seer_close_eyes.mp3
                        val seerOpenAudio = getOpenEyesAudio(NightSubPhase.SEER_PICK)
                        val seerCloseAudio = getCloseEyesAudio(NightSubPhase.SEER_RESULT)
                        if (seerOpenAudio != null) audioFiles.add(seerOpenAudio)
                        if (seerCloseAudio != null) audioFiles.add(seerCloseAudio)
                        // Skip SEER_RESULT since we already handled it
                        continue
                    } else {
                        // Only SEER_PICK skipped (unusual case)
                        val openEyesAudio = getOpenEyesAudio(skippedRole)
                        if (openEyesAudio != null) audioFiles.add(openEyesAudio)
                    }
                } else if (skippedRole == NightSubPhase.SEER_RESULT) {
                    // SEER_RESULT without SEER_PICK - check if SEER_PICK was processed
                    val seerPickSkipped = skippedRoles.contains(NightSubPhase.SEER_PICK)
                    if (seerPickSkipped) {
                        // Already handled together with SEER_PICK above
                        continue
                    } else {
                        // Only SEER_RESULT skipped (unusual case)
                        val closeEyesAudio = getCloseEyesAudio(skippedRole)
                        if (closeEyesAudio != null) audioFiles.add(closeEyesAudio)
                    }
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