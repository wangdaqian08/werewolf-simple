package com.werewolf.game.night

import com.werewolf.model.AudioSequence
import com.werewolf.model.NightSubPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.util.Optional

/**
 * Schedules delayed night phase transitions:
 * - WAITING → WEREWOLF_PICK (5 seconds)
 * - Dead role transitions (20 seconds)
 * Lives in a separate bean to avoid a circular dependency with NightOrchestrator.
 */
@Component
class NightWaitingScheduler(
    private val nightOrchestrator: NightOrchestrator,
    private val stompPublisher: com.werewolf.service.StompPublisher,
    private val contextLoader: com.werewolf.service.GameContextLoader,
    private val nightPhaseRepository: com.werewolf.repository.NightPhaseRepository,
    private val coroutineScope: CoroutineScope, // 新增：协程作用域
) {

    val log: Logger = LoggerFactory.getLogger(NightWaitingScheduler::class.java)

    /**
     * 使用协程调度延迟推进
     * 返回 Job 以便调用者可以取消或监控
     */
    fun scheduleAdvance(gameId: Int, delayMs: Long = 5_000, targetSubPhase: NightSubPhase? = null): Job {
        log.info("[NightWaitingScheduler] Scheduling advance for game $gameId with delay ${delayMs}ms to ${targetSubPhase ?: "WEREWOLF_PICK"}")
        
        return coroutineScope.launch {
            try {
                delay(delayMs)
                log.info("[NightWaitingScheduler] Delay completed for game $gameId, advancing...")

                // When targetSubPhase is null (default), it means WAITING → first real phase
                if (targetSubPhase == null || targetSubPhase == NightSubPhase.WAITING) {
                    log.info("[NightWaitingScheduler] Calling advanceFromWaiting for game $gameId")
                    nightOrchestrator.advanceFromWaiting(gameId)
                } else {
                    log.info("[NightWaitingScheduler] Calling advanceToSubPhase for game $gameId to $targetSubPhase")
                    nightOrchestrator.advanceToSubPhase(gameId, targetSubPhase)
                }

                log.info("[NightWaitingScheduler] Advance completed for game $gameId")
            } catch (e: Exception) {
                log.error("[NightWaitingScheduler] ERROR during advance for game $gameId: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 使用协程调度音频延迟
     * 提供延迟的音频播放，同时保持立即的 UI 更新
     * 返回 Job 以便调用者可以取消或监控
     */
    fun scheduleAudioDelay(
        gameId: Int,
        audioSequence: AudioSequence,
        delayMs: Long,
    ): Job {
        log.info("[NightWaitingScheduler] Scheduling audio delay for game $gameId with delay ${delayMs}ms")
        
        return coroutineScope.launch {
            try {
                delay(delayMs)
                log.info("[NightWaitingScheduler] Audio delay completed for game $gameId, broadcasting audio sequence...")
                stompPublisher.broadcastGame(gameId, com.werewolf.game.DomainEvent.AudioSequence(gameId, audioSequence))
                log.info("[NightWaitingScheduler] Audio sequence broadcast completed for game $gameId")
            } catch (e: Exception) {
                log.error("[NightWaitingScheduler] ERROR during audio delay for game $gameId: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 使用协程调度死亡角色音频并自动推进
     * 播放音频序列并暂停，然后推进到下一个阶段或白天
     * 返回 Job 以便调用者可以取消或监控
     */
    fun scheduleDeadRoleAudio(
        gameId: Int,
        dayNumber: Int,
        audioSequence: AudioSequence,
        delayMs: Long,
        nextSubPhase: NightSubPhase?,
        autoAdvanceToDay: Boolean,
    ): Job {
        log.info("[NightWaitingScheduler] Scheduling dead role audio for game $gameId, nextSubPhase=$nextSubPhase, autoAdvanceToDay=$autoAdvanceToDay, audioFiles=${audioSequence.audioFiles}")

        return coroutineScope.launch {
            try {
                // Play open-eyes audio — must be present; missing audio is a programming error
                val firstAudio = audioSequence.audioFiles.getOrNull(0)
                    ?: error("[scheduleDeadRoleAudio] game=$gameId: open-eyes audio missing (audioFiles=${audioSequence.audioFiles})")
                log.info("[scheduleDeadRoleAudio] game=$gameId: playing open-eyes audio '$firstAudio'")
                broadcastAudioEvent(gameId, firstAudio)
                delay(2000) // wait for audio to finish

                // Pause to simulate the dead role's operation time
                delay(delayMs)

                // Play close-eyes audio — must be present
                val secondAudio = audioSequence.audioFiles.getOrNull(1)
                    ?: error("[scheduleDeadRoleAudio] game=$gameId: close-eyes audio missing (audioFiles=${audioSequence.audioFiles})")
                log.info("[scheduleDeadRoleAudio] game=$gameId: playing close-eyes audio '$secondAudio'")
                broadcastAudioEvent(gameId, secondAudio)
                delay(2000) // wait for audio to finish

                // Inter-role gap: give players 5 seconds to register the previous role
                // closing before the next role opens eyes
                if (nextSubPhase != null) {
                    log.info("[scheduleDeadRoleAudio] game=$gameId: 5s gap before next role $nextSubPhase")
                    delay(5_000)
                }

                // After audio playback, advance to next phase or day
                if (autoAdvanceToDay) {
                    log.info("[NightWaitingScheduler] Auto-advancing to day for game $gameId")
                    val context = contextLoader.load(gameId)
                    val nightPhase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, dayNumber)
                        .orElseThrow { IllegalStateException("NightPhase not found for game $gameId, dayNumber $dayNumber") }
                    nightOrchestrator.resolveNightKills(context, nightPhase)
                } else if (nextSubPhase != null) {
                    log.info("[NightWaitingScheduler] Auto-advancing to next sub-phase $nextSubPhase for game $gameId")
                    val nightPhase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, dayNumber)
                        .orElseThrow { IllegalStateException("NightPhase not found for game $gameId, dayNumber $dayNumber") }
                    nightOrchestrator.advance(gameId, nightPhase.subPhase)
                }
            } catch (e: Exception) {
                log.error("[NightWaitingScheduler] ERROR during dead role audio for game $gameId: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun broadcastAudioEvent(gameId: Int, audioFile: String, subPhase: String = "") {
        val audioSequence = AudioSequence(
            id = "${gameId}-${System.currentTimeMillis()}-${audioFile}",
            phase = com.werewolf.model.GamePhase.NIGHT,
            subPhase = subPhase,
            audioFiles = listOf(audioFile),
            priority = 5,
        )
        stompPublisher.broadcastGame(gameId, com.werewolf.game.DomainEvent.AudioSequence(gameId, audioSequence))
    }

    /**
     * 使用协程调度最后一个特殊角色的闭眼音频并推进到白天
     * 播放最后一个特殊角色的 close_eyes.mp3，然后解析夜阶段击杀
     * 返回 Job 以便调用者可以取消或监控
     */
    fun scheduleLastSpecialRoleCloseEyesAndAdvanceToDay(
        gameId: Int,
        dayNumber: Int,
        closeEyesAudio: String,
    ): Job {
        log.info("[NightWaitingScheduler] Scheduling last special role close eyes audio for game $gameId")
        
        return coroutineScope.launch {
            try {
                // Update nightPhase subPhase to WAITING so frontend can show waiting UI
                val nightPhase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, dayNumber)
                    .orElseThrow { IllegalStateException("NightPhase not found for game $gameId, dayNumber $dayNumber") }
                nightPhase.subPhase = NightSubPhase.WAITING
                nightPhaseRepository.save(nightPhase)
                
                // Broadcast subPhaseChanged event so frontend updates UI
                stompPublisher.broadcastGame(gameId, com.werewolf.game.DomainEvent.NightSubPhaseChanged(gameId, NightSubPhase.WAITING))
                
                // Play close eyes audio
                log.info("[scheduleLastSpecialRoleCloseEyesAndAdvanceToDay] game=$gameId: playing close-eyes audio '$closeEyesAudio'")
                broadcastAudioEvent(gameId, closeEyesAudio, NightSubPhase.WAITING.name)
                delay(2000) // Wait for audio to play
                
                // Advance to day
                log.info("[NightWaitingScheduler] Advancing to day for game $gameId")
                val context = contextLoader.load(gameId)
                nightOrchestrator.resolveNightKills(context, nightPhase)
            } catch (e: Exception) {
                log.error("[NightWaitingScheduler] ERROR during last special role close eyes audio for game $gameId: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 使用协程调度存活角色转换音频
     * 播放 close_eyes 音频，等待一段时间，然后播放 open_eyes 音频
     * 这为玩家提供了角色之间的过渡时间
     * 
     * @param gameId 游戏ID
     * @param closeEyesAudio 闭眼音频文件名（可选，为null则跳过）
     * @param openEyesAudio 睁眼音频文件名（可选，为null则跳过）
     * @param gapMs 两个音频之间的间隔时间（毫秒）
     * @return Job 以便调用者可以取消或监控
     */
    fun scheduleAliveRoleTransition(
        gameId: Int,
        closeEyesAudio: String?,
        openEyesAudio: String?,
        gapMs: Long = 3000L,
    ): Job {
        log.info("[NightWaitingScheduler] Scheduling alive role transition for game $gameId, closeEyes=$closeEyesAudio, openEyes=$openEyesAudio, gap=${gapMs}ms")

        return coroutineScope.launch {
            try {
                // 1. Play close-eyes audio if present
                if (closeEyesAudio != null) {
                    log.info("[scheduleAliveRoleTransition] game=$gameId: playing close-eyes audio '$closeEyesAudio'")
                    broadcastAudioEvent(gameId, closeEyesAudio)
                    delay(2000) // Wait for audio to finish
                }

                // 2. Gap between roles - gives players time to process
                if (closeEyesAudio != null && openEyesAudio != null) {
                    log.info("[scheduleAliveRoleTransition] game=$gameId: ${gapMs}ms gap between roles")
                    delay(gapMs)
                }

                // 3. Play open-eyes audio if present
                if (openEyesAudio != null) {
                    log.info("[scheduleAliveRoleTransition] game=$gameId: playing open-eyes audio '$openEyesAudio'")
                    broadcastAudioEvent(gameId, openEyesAudio)
                    delay(1000) // Small delay for audio to start
                }
            } catch (e: Exception) {
                log.error("[NightWaitingScheduler] ERROR during alive role transition for game $gameId: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}