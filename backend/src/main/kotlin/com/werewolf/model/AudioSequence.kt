package com.werewolf.model

/**
 * Audio sequence for game audio playback
 * Backend calculates the sequence, frontend plays it in order
 */
data class AudioSequence(
    val id: String,                    // Unique sequence ID for deduplication
    val phase: GamePhase,              // Current game phase
    val subPhase: String?,             // Current sub-phase (can be null)
    val audioFiles: List<String>,      // Audio files to play in order
    val priority: Int = 0,             // Priority for overlapping sequences
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Check if this sequence should replace another sequence
     * Newer sequences with same or higher priority replace older ones
     */
    fun shouldReplace(other: AudioSequence?): Boolean {
        if (other == null) return true
        if (this.priority > other.priority) return true
        if (this.timestamp > other.timestamp) return true
        return false
    }
}