package com.werewolf.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class AudioTrackDto(
    val id: String?,
    val filename: String?,
    val displayName: String,
)

@Component
class BgmTrackRegistry(
    private val objectMapper: ObjectMapper,
) {
    @Volatile private var cached: List<AudioTrackDto> = emptyList()
    @Volatile private var cachedAt: Long = 0L
    private val ttlMs: Long = 60_000L

    fun list(): List<AudioTrackDto> {
        val now = System.currentTimeMillis()
        if (cached.isNotEmpty() && now - cachedAt < ttlMs) return cached
        return refresh()
    }

    @Synchronized
    fun refresh(): List<AudioTrackDto> {
        val resolver = PathMatchingResourcePatternResolver(this::class.java.classLoader)
        val resources = try {
            resolver.getResources("classpath:/static/audio/bgm/*.mp3")
        } catch (e: Exception) {
            emptyArray()
        }

        val displayNames: Map<String, String> = try {
            val sidecar = resolver.getResource("classpath:/static/audio/bgm/tracks.json")
            if (sidecar.exists()) {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(sidecar.inputStream, Map::class.java) as Map<String, String>
            } else emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        val tracks = resources
            .mapNotNull { it.filename }
            .filter { it.endsWith(".mp3", ignoreCase = true) }
            .sorted()
            .map { filename ->
                AudioTrackDto(
                    id = filename,
                    filename = filename,
                    displayName = displayNames[filename] ?: deriveDisplayName(filename),
                )
            }

        val result = listOf(AudioTrackDto(id = null, filename = null, displayName = "无 (None)")) + tracks
        cached = result
        cachedAt = System.currentTimeMillis()
        return result
    }

    fun isValidTrack(filename: String?): Boolean {
        if (filename == null) return true
        return list().any { it.filename == filename }
    }

    private fun deriveDisplayName(filename: String): String {
        return filename
            .removeSuffix(".mp3")
            .removeSuffix(".MP3")
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}

@RestController
class AudioTracksController(
    private val registry: BgmTrackRegistry,
) {
    @GetMapping("/api/audio/tracks")
    fun list(): List<AudioTrackDto> = registry.list()
}
