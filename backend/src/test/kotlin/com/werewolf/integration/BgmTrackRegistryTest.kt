package com.werewolf.integration

import com.werewolf.controller.AudioTrackDto
import com.werewolf.controller.BgmTrackRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.test.context.ActiveProfiles

/**
 * The registry swallows sidecar read errors and falls back to filename-derived
 * names, so a broken JSON mapper would only show up as wrong display names.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BgmTrackRegistryTest {

    @Autowired lateinit var registry: BgmTrackRegistry

    @Test
    fun `display names come from the tracks json sidecar`() {
        assertThat(registry.refresh()).containsExactly(
            AudioTrackDto(null, null, "无 (None)"),
            AudioTrackDto("suspicion.mp3", "suspicion.mp3", "柯南-怀疑 / Detective Conan"),
            AudioTrackDto("心愿便利贴.mp3", "心愿便利贴.mp3", "心愿便利贴 / Wishful Sticker"),
        )
    }
}
