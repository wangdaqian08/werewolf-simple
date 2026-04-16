package com.werewolf.game.night

import com.werewolf.audio.RoleRegistry
import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.game.role.RoleHandler
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.service.GameContextLoader
import com.werewolf.service.StompPublisher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

@Service
class NightOrchestrator(
    private val handlers: List<RoleHandler>,
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val nightPhaseRepository: NightPhaseRepository,
    private val winConditionChecker: WinConditionChecker,
    private val stompPublisher: StompPublisher,
    private val contextLoader: GameContextLoader,
    @Lazy private val nightWaitingScheduler: NightWaitingScheduler,
    private val audioService: com.werewolf.service.AudioService,
    private val coroutineScope: CoroutineScope, // 新增：协程作用域
    private val actionLogService: com.werewolf.service.ActionLogService,
) {
    private val log = LoggerFactory.getLogger(NightOrchestrator::class.java)

    // 协程相关字段
    private val activeNightJobs = ConcurrentHashMap<Int, Job>() // 存储正在进行的夜阶段任务
    private val pendingActions = ConcurrentHashMap<PlayerRole, CompletableDeferred<com.werewolf.game.action.GameActionResult?>>() // 存储角色动作的 CompletableDeferred
    /**
     * Ordered night sub-phases for this game based on which roles are active.
     * Werewolves are always included; other roles follow the room config.
     */
    fun nightSequence(context: GameContext): List<NightSubPhase> {
        val activeRoles = mutableSetOf<PlayerRole>(PlayerRole.WEREWOLF)
        if (context.room.hasSeer) activeRoles.add(PlayerRole.SEER)
        if (context.room.hasWitch) activeRoles.add(PlayerRole.WITCH)
        if (context.room.hasGuard) activeRoles.add(PlayerRole.GUARD)
        // Note: IDIOT has no night sub-phases, so we don't need to check for it

        val sequence = handlers
            .filter { it.role in activeRoles }
            .flatMap { it.nightSubPhases() }
        
        log.info("[nightSequence] gameId=${context.gameId}, activeRoles=$activeRoles, sequence=$sequence")
        return sequence
    }

    fun firstSubPhase(context: GameContext): NightSubPhase =
        nightSequence(context).firstOrNull() ?: NightSubPhase.WEREWOLF_PICK

    /**
     * Initialise a new night phase and transition the game to NIGHT.
     * Called from GamePhasePipeline (first night) and VotingPipeline (subsequent nights).
     *
     * @param withWaiting When true (no-sheriff first night), starts in WAITING sub-phase
     *   and schedules an automatic 5-second transition to WEREWOLF_PICK.
     */
    @Transactional
    fun initNight(gameId: Int, newDayNumber: Int, previousGuardTarget: String? = null, withWaiting: Boolean = false) {
        val context = contextLoader.load(gameId)
        requireNotNull(context.room.config) {
            "[initNight] game=$gameId: Room.config must not be null — set GameConfig before starting night phase"
        }
        val initialSubPhase = if (withWaiting) NightSubPhase.WAITING else firstSubPhase(context)

        val nightPhase = NightPhase(
            gameId = gameId,
            dayNumber = newDayNumber,
            subPhase = initialSubPhase,
            prevGuardTargetUserId = previousGuardTarget,
        )
        nightPhaseRepository.save(nightPhase)

        val game = context.game
        game.phase = GamePhase.NIGHT
        game.subPhase = null
        game.dayNumber = newDayNumber
        gameRepository.save(game)

        // Calculate audio sequence for NIGHT phase
        val audioSequence = audioService.calculatePhaseTransition(
            gameId = gameId,
            oldPhase = GamePhase.DAY_DISCUSSION, // Assuming coming from DAY
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = initialSubPhase.name,
            room = context.room,
        )

        // Send both events
        stompPublisher.broadcastGame(gameId, DomainEvent.PhaseChanged(gameId, GamePhase.NIGHT, initialSubPhase.name))
        stompPublisher.broadcastGame(gameId, DomainEvent.AudioSequence(gameId, audioSequence))

        if (withWaiting) {
            nightWaitingScheduler.scheduleAdvance(gameId)
        }
    }

    /**
     * Checks if the current night sub-phase is stuck (role has no alive players) and advances if needed.
     * Call this on backend startup or when loading a game context to recover from restarts.
     */
    @Transactional
    fun recoverStuckNightPhase(gameId: Int) {
        val context = contextLoader.load(gameId)
        val nightPhase = context.nightPhase ?: return

        // Only recover if game is in NIGHT phase and not COMPLETE
        if (context.game.phase != GamePhase.NIGHT || nightPhase.subPhase == NightSubPhase.COMPLETE) {
            return
        }

        val currentSubPhase = nightPhase.subPhase
        val roleForCurrentPhase = getRoleForSubPhase(currentSubPhase)

        if (roleForCurrentPhase != null) {
            val hasAlivePlayersForRole = context.alivePlayers.any { it.role == roleForCurrentPhase }
            if (!hasAlivePlayersForRole) {
                // Current role has no alive players - advance to next sub-phase
                advance(gameId, currentSubPhase)
            }
        }
    }

    /**
     * Advances the night from WAITING to the first real sub-phase (WEREWOLF_PICK).
     * Called automatically by [NightWaitingScheduler] after the 5-second countdown.
     */
    @Transactional
    fun advanceFromWaiting(gameId: Int) {
        val context = contextLoader.load(gameId)
        val nightPhase = context.nightPhase ?: return
        if (nightPhase.subPhase != NightSubPhase.WAITING) return // idempotent guard
        val firstRealSubPhase = firstSubPhase(context)
        nightPhase.subPhase = firstRealSubPhase
        nightPhaseRepository.save(nightPhase)

        // Calculate audio sequence for this transition
        val audioSequence = audioService.calculateNightSubPhaseTransition(
            gameId = gameId,
            oldSubPhase = NightSubPhase.WAITING,
            newSubPhase = firstRealSubPhase,
        )

        // Send both events
        stompPublisher.broadcastGame(gameId, DomainEvent.NightSubPhaseChanged(gameId, firstRealSubPhase))
        stompPublisher.broadcastGame(gameId, DomainEvent.AudioSequence(gameId, audioSequence))
    }

    /**
     * Advances to a specific sub-phase after a scheduled delay.
     * Used when a role has no alive players and we want to wait 20 seconds before moving to the next role.
     */
    @Transactional
    fun advanceToSubPhase(gameId: Int, targetSubPhase: NightSubPhase?) {
        if (targetSubPhase == null) return
        val context = contextLoader.load(gameId)
        val nightPhase = context.nightPhase ?: return
        val oldSubPhase = nightPhase.subPhase
        nightPhase.subPhase = targetSubPhase
        nightPhaseRepository.save(nightPhase)

        // Calculate audio sequence for this transition
        val audioSequence = audioService.calculateNightSubPhaseTransition(
            gameId = gameId,
            oldSubPhase = oldSubPhase,
            newSubPhase = targetSubPhase,
        )

        // Send both events
        stompPublisher.broadcastGame(gameId, DomainEvent.NightSubPhaseChanged(gameId, targetSubPhase))
        stompPublisher.broadcastGame(gameId, DomainEvent.AudioSequence(gameId, audioSequence))
    }

    /**
     * Called after a night role action completes.
     * Advances to the next sub-phase in the sequence, or resolves night kills and moves to DAY.
     */
    @Transactional
    fun advance(gameId: Int, completedSubPhase: NightSubPhase) {
        val context = contextLoader.load(gameId)
        val nightPhase = context.nightPhase ?: return

        val sequence = nightSequence(context)
        val currentIdx = sequence.indexOf(completedSubPhase)
        if (currentIdx < 0) return

        val nextSubPhase = sequence.getOrNull(currentIdx + 1)
        
        // Debug logging
        log.info("[advance] gameId=$gameId, completedSubPhase=$completedSubPhase, sequence=$sequence, currentIdx=$currentIdx, nextSubPhase=$nextSubPhase")
        log.info("[advance] alivePlayers=${context.alivePlayers.map { "${it.userId}:${it.role}" }}")

        if (nextSubPhase == null) {
            log.info("[advance] No next sub-phase, last special role is $completedSubPhase")
            
            // Get close eyes audio for the last special role
            val closeEyesAudio = getCloseEyesAudioForSubPhase(completedSubPhase)
            
            if (closeEyesAudio != null) {
                log.info("[advance] Playing close eyes audio for last special role: $closeEyesAudio")
                // Play close eyes audio, then advance to day
                nightWaitingScheduler.scheduleLastSpecialRoleCloseEyesAndAdvanceToDay(gameId, nightPhase.dayNumber, closeEyesAudio)
            } else {
                log.info("[advance] No close eyes audio for $completedSubPhase, resolving night kills directly")
                resolveNightKills(context, nightPhase)
            }
        } else {
            // Special handling: if seer is dead, SEER_PICK → SEER_RESULT
            val isSeerDead = nextSubPhase == NightSubPhase.SEER_PICK && 
                             !context.alivePlayers.any { it.role == PlayerRole.SEER }

            if (isSeerDead) {
                // Seer is dead: skip SEER_PICK and go directly to SEER_RESULT
                nightPhase.subPhase = NightSubPhase.SEER_RESULT
                nightPhaseRepository.save(nightPhase)
                
                // Send UI event
                stompPublisher.broadcastGame(gameId, DomainEvent.NightSubPhaseChanged(gameId, NightSubPhase.SEER_RESULT))
                
                // Calculate audio sequence: seer_open_eyes.mp3 → pause → seer_close_eyes.mp3
                val audioSequence = audioService.calculateDeadRoleAudioSequence(
                    gameId = gameId,
                    skippedRoles = listOf(NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT),
                    targetSubPhase = NightSubPhase.SEER_RESULT
                )
                
                // Find the next phase after SEER_RESULT
                val seerResultIdx = sequence.indexOf(NightSubPhase.SEER_RESULT)
                val phaseAfterSeerResultIdx = seerResultIdx + 1
                val nextSubPhaseAfterSeerResult = sequence.getOrNull(phaseAfterSeerResultIdx)
                
                // Schedule audio playback and auto-advance
                nightWaitingScheduler.scheduleDeadRoleAudio(
                    gameId = gameId,
                    dayNumber = nightPhase.dayNumber,
                    audioSequence = audioSequence,
                    delayMs = 5000L, // Pause for 5 seconds
                    nextSubPhase = nextSubPhaseAfterSeerResult,
                    autoAdvanceToDay = false, // Not the last special role
                )
            } else {
                // Check if the role for next sub-phase is alive
                val nextRole = getRoleForSubPhase(nextSubPhase)
                val isRoleAlive = nextRole == null || context.alivePlayers.any { it.role == nextRole }
                
                log.info("[advance] gameId=$gameId, nextSubPhase=$nextSubPhase, nextRole=$nextRole, isRoleAlive=$isRoleAlive")

                if (!isRoleAlive) {
                    // Role is dead: enter the phase, play audio with pause, then advance
                    nightPhase.subPhase = nextSubPhase
                    nightPhaseRepository.save(nightPhase)
                    
                    // Send UI event
                    stompPublisher.broadcastGame(gameId, DomainEvent.NightSubPhaseChanged(gameId, nextSubPhase))
                    
                    // Calculate audio sequence: open_eyes.mp3 → close_eyes.mp3
                    val audioSequence = audioService.calculateDeadRoleAudioSequence(
                        gameId = gameId,
                        skippedRoles = listOf(nextSubPhase),
                        targetSubPhase = nextSubPhase
                    )
                    
                    // Check if this is the last special role
                    // nextSubPhaseIdx is the index of nextSubPhase (currentIdx + 1)
                    // phaseAfterNextIdx is the index of the phase after nextSubPhase (currentIdx + 2)
                    val nextSubPhaseIdx = currentIdx + 1
                    val phaseAfterNextIdx = nextSubPhaseIdx + 1
                    val isLastSpecialRole = nextSubPhaseIdx == sequence.lastIndex
                    
                    log.info("[advance] gameId=$gameId, nextSubPhase=$nextSubPhase, nextSubPhaseIdx=$nextSubPhaseIdx, sequence.lastIndex=${sequence.lastIndex}, isLastSpecialRole=$isLastSpecialRole")
                    
                    // Schedule audio playback and auto-advance
                    nightWaitingScheduler.scheduleDeadRoleAudio(
                        gameId = gameId,
                        dayNumber = nightPhase.dayNumber,
                        audioSequence = audioSequence,
                        delayMs = 5000L, // Pause for 5 seconds
                        nextSubPhase = if (isLastSpecialRole) null else sequence[phaseAfterNextIdx],
                        autoAdvanceToDay = isLastSpecialRole,
                    )
                } else {
                    // Role is alive: advance normally with separate audio broadcasts
                    nightPhase.subPhase = nextSubPhase
                    nightPhaseRepository.save(nightPhase)
                    
                    // Get separate audio for close_eyes and open_eyes
                    // Skip close-eyes when entering SEER_RESULT: seer is still awake viewing the result.
                    // Close-eyes plays later when advancing FROM SEER_RESULT to the next role.
                    val closeEyesAudio = if (nextSubPhase == NightSubPhase.SEER_RESULT) null
                                         else audioService.calculateCloseEyesAudio(completedSubPhase)
                    val openEyesAudio = audioService.calculateOpenEyesAudio(nextSubPhase)
                    
                    // Send UI update immediately
                    stompPublisher.broadcastGame(gameId, DomainEvent.NightSubPhaseChanged(gameId, nextSubPhase))
                    
                    // Schedule audio playback with gap between close_eyes and open_eyes
                    nightWaitingScheduler.scheduleAliveRoleTransition(
                        gameId = gameId,
                        closeEyesAudio = closeEyesAudio,
                        openEyesAudio = openEyesAudio,
                        gapMs = 3000L, // 3 second gap between roles
                    )
                }
            }
        }
    }

    private fun getRoleForSubPhase(subPhase: NightSubPhase): PlayerRole? {
        return when (subPhase) {
            NightSubPhase.WEREWOLF_PICK -> PlayerRole.WEREWOLF
            NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT -> PlayerRole.SEER
            NightSubPhase.WITCH_ACT -> PlayerRole.WITCH
            NightSubPhase.GUARD_PICK -> PlayerRole.GUARD
            else -> null
        }
    }

    private fun getCloseEyesAudioForSubPhase(subPhase: NightSubPhase): String? {
        val role = getRoleForSubPhase(subPhase) ?: return null
        return RoleRegistry.getCloseEyesAudio(role)
    }

    @Transactional
    fun resolveNightKills(context: GameContext, nightPhase: NightPhase) {
        val gameId = context.gameId
        val kills = mutableListOf<String>()

        val wolfTarget = nightPhase.wolfTargetUserId
        if (wolfTarget != null) {
            val antidoteSaved = nightPhase.witchAntidoteUsed
            val guardSaved = nightPhase.guardTargetUserId == wolfTarget
            if (!antidoteSaved && !guardSaved) {
                kills.add(wolfTarget)
            }
        }

        val poisonTarget = nightPhase.witchPoisonTargetUserId
        if (poisonTarget != null) {
            kills.add(poisonTarget)
        }

        // Apply kills
        for (killId in kills.distinct()) {
            gamePlayerRepository.findByGameIdAndUserId(gameId, killId).ifPresent { player ->
                player.alive = false
                // TODO consider batch action on save
                gamePlayerRepository.save(player)
            }
        }

        // Record public action log (cause intentionally omitted — no wolf/witch attribution)
        actionLogService.recordNightDeaths(gameId, nightPhase.dayNumber, kills.distinct())

        nightPhase.subPhase = NightSubPhase.COMPLETE
        nightPhaseRepository.save(nightPhase)

        // Check win condition
        val updatedContext = contextLoader.load(gameId)
        val winner = winConditionChecker.check(updatedContext.alivePlayers, updatedContext.room.winCondition)

        if (winner != null) {
            // Update game state immediately (before transaction commit)
            context.game.apply {
                this.winner = winner
                phase = GamePhase.GAME_OVER
                endedAt = LocalDateTime.now()
            }
            gameRepository.save(context.game)

            // Broadcast events after transaction commit
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        try {
                            stompPublisher.broadcastGame(gameId, DomainEvent.NightResult(gameId, kills.distinct()))
                        } catch (e: Exception) {
                            log.error("[resolveNightKills] Failed to broadcast NightResult for game $gameId", e)
                        }
                        
                        try {
                            stompPublisher.broadcastGame(gameId, DomainEvent.GameOver(gameId, winner))
                        } catch (e: Exception) {
                            log.error("[resolveNightKills] Failed to broadcast GameOver for game $gameId", e)
                        }
                    }
                    
                    override fun afterCompletion(status: Int) {
                        if (status != STATUS_COMMITTED) {
                            log.error("[resolveNightKills] Transaction not committed for game $gameId, status: $status")
                        }
                    }
                })
            } else {
                stompPublisher.broadcastGame(gameId, DomainEvent.NightResult(gameId, kills.distinct()))
                stompPublisher.broadcastGame(gameId, DomainEvent.GameOver(gameId, winner))
            }
        } else {
            val game = updatedContext.game
            game.phase = GamePhase.DAY_DISCUSSION
            game.subPhase = DaySubPhase.RESULT_HIDDEN.name
            gameRepository.save(game)

            // Calculate audio sequence for DAY phase

                        val audioSequence = audioService.calculatePhaseTransition(

                            gameId = gameId,

                            oldPhase = GamePhase.NIGHT,

                            newPhase = GamePhase.DAY_DISCUSSION,

                            oldSubPhase = null,

                            newSubPhase = DaySubPhase.RESULT_HIDDEN.name,

                            room = updatedContext.room,

                        )

            

                        // Register transaction synchronization to broadcast after commit
                        if (TransactionSynchronizationManager.isActualTransactionActive()) {
                            TransactionSynchronizationManager.registerSynchronization(object :
                                org.springframework.transaction.support.TransactionSynchronization {
                                override fun afterCommit() {
                                    // Broadcast NightResult first
                                    try {
                                        stompPublisher.broadcastGame(gameId, DomainEvent.NightResult(gameId, kills.distinct()))
                                    } catch (e: Exception) {
                                        log.error("[resolveNightKills] Failed to broadcast NightResult for game $gameId", e)
                                    }
                                    
                                    // Broadcast PhaseChanged - critical for test to pass
                                    try {
                                        stompPublisher.broadcastGame(
                                            gameId,
                                            DomainEvent.PhaseChanged(gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_HIDDEN.name)
                                        )
                                    } catch (e: Exception) {
                                        log.error("[resolveNightKills] CRITICAL: Failed to broadcast PhaseChanged for game $gameId", e)
                                    }
                                    
                                    // Broadcast AudioSequence - failure should not block phase transition
                                    try {
                                        stompPublisher.broadcastGame(gameId, DomainEvent.AudioSequence(gameId, audioSequence))
                                    } catch (e: Exception) {
                                        log.error("[resolveNightKills] Failed to broadcast AudioSequence for game $gameId (non-critical)", e)
                                    }
                                    
                                    // Fire onDayEnter hooks - failure should not block phase transition
                                    try {
                                        val activeRoles = updatedContext.alivePlayers.map { it.role }.toSet()
                                        handlers.filter { it.role in activeRoles }.forEach { handler ->
                                            try {
                                                val events = handler.onDayEnter(updatedContext)
                                                events.forEach { stompPublisher.broadcastGame(gameId, it) }
                                            } catch (e: Exception) {
                                                log.error("[resolveNightKills] Failed to execute onDayEnter for ${handler.role}, game $gameId", e)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        log.error("[resolveNightKills] Failed to execute onDayEnter hooks for game $gameId (non-critical)", e)
                                    }
                                }
                                
                                override fun afterCompletion(status: Int) {
                                    if (status != STATUS_COMMITTED) {
                                        log.error("[resolveNightKills] Transaction not committed for game $gameId, status: $status")
                                    }
                                }
                            })
                        } else {
                            // If no transaction is active, broadcast immediately
                            stompPublisher.broadcastGame(gameId, DomainEvent.NightResult(gameId, kills.distinct()))
                            stompPublisher.broadcastGame(
                                gameId,
                                DomainEvent.PhaseChanged(gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_HIDDEN.name)
                            )
                            stompPublisher.broadcastGame(gameId, DomainEvent.AudioSequence(gameId, audioSequence))
                            
                            // Fire onDayEnter hooks
                            val activeRoles = updatedContext.alivePlayers.map { it.role }.toSet()
                            handlers.filter { it.role in activeRoles }.forEach { handler ->
                                val events = handler.onDayEnter(updatedContext)
                                events.forEach { stompPublisher.broadcastGame(gameId, it) }
                            }
                        }
        }
    }

    /**
     * Advances to a specific sub-phase with immediate UI update and delayed audio.
     * Used when skipping dead roles to provide immediate feedback while maintaining atmosphere.
     *
     * Implementation based on expert team review:
     * - UI updates immediately for better user experience
     * - Audio plays with delay to simulate dead role operation time
     * - Dead roles get complete audio sequence: open eyes → close eyes
     * - Backend controls all audio sending, frontend only plays
     */
    @Transactional
    fun advanceToSubPhaseWithDelayedAudio(
        gameId: Int,
        skippedRoles: List<NightSubPhase>,
        targetSubPhase: NightSubPhase,
        audioDelayMs: Long,
    ) {
        val context = contextLoader.load(gameId)
        val nightPhase = context.nightPhase ?: return

        // Update phase immediately (UI update)
        nightPhase.subPhase = targetSubPhase
        nightPhaseRepository.save(nightPhase)

        // Send UI event immediately
        stompPublisher.broadcastGame(gameId, DomainEvent.NightSubPhaseChanged(gameId, targetSubPhase))

        // Calculate audio sequence for dead roles
        val audioSequence = audioService.calculateDeadRoleAudioSequence(
            gameId = gameId,
            skippedRoles = skippedRoles,
            targetSubPhase = targetSubPhase
        )

        // Schedule audio delay
        nightWaitingScheduler.scheduleAudioDelay(gameId, audioSequence, audioDelayMs)
    }

    /**
     * 使用协程启动夜阶段
     * 这是符合 ADR-010 的主入口点，使用 Kotlin 协程来管理夜阶段流程
     * 
     * @param gameId 游戏ID
     * @param newDayNumber 新的白天编号
     * @param previousGuardTarget 上一个守卫保护的目标ID
     * @param withWaiting 是否以WAITING子阶段开始（无警长第一夜）
     */
    fun startNightPhase(gameId: Int, newDayNumber: Int, previousGuardTarget: String? = null, withWaiting: Boolean = false): Job {
        // 取消之前的任务（如果存在）
        activeNightJobs[gameId]?.cancel()
        
        val job = coroutineScope.launch {
            try {
                executeNightSequence(gameId, newDayNumber, previousGuardTarget, withWaiting)
            } catch (e: Exception) {
                log.error("[startNightPhase] Night phase failed for game $gameId", e)
            } finally {
                activeNightJobs.remove(gameId)
            }
        }
        
        activeNightJobs[gameId] = job
        log.info("[startNightPhase] Started night phase for game $gameId")
        return job
    }

    /**
     * 提交角色动作
     * 使用 CompletableDeferred 来完成等待中的协程
     */
    fun submitAction(gameId: Int, role: PlayerRole, action: com.werewolf.game.action.GameActionResult) {
        val deferred = pendingActions[role]
        if (deferred != null) {
            log.info("[submitAction] Submitting action for game $gameId, role $role")
            deferred.complete(action)
        } else {
            log.warn("[submitAction] No pending action for game $gameId, role $role")
        }
    }

    /**
     * 取消夜阶段
     * 取消所有正在进行的协程任务并清理状态
     */
    fun cancelNightPhase(gameId: Int) {
        log.info("[cancelNightPhase] Cancelling night phase for game $gameId")
        activeNightJobs[gameId]?.cancel()
        activeNightJobs.remove(gameId)
        pendingActions.clear()
    }

    /**
     * 使用协程执行夜阶段序列
     * 这是核心的协程逻辑，按照 ADR-010 的描述实现
     * 
     * @param gameId 游戏ID
     * @param newDayNumber 新的白天编号
     * @param previousGuardTarget 上一个守卫保护的目标ID
     * @param withWaiting 是否以WAITING子阶段开始
     */
    private suspend fun executeNightSequence(gameId: Int, newDayNumber: Int, previousGuardTarget: String? = null, withWaiting: Boolean = false) {
        // 初始化夜阶段
        initNightInternal(gameId, newDayNumber, previousGuardTarget, withWaiting)
        
        val context = contextLoader.load(gameId)
        val nightSequence = nightSequence(context)
        
        log.info("[executeNightSequence] Starting night sequence for game $gameId: $nightSequence")
        
        for (subPhase in nightSequence) {
            val role = getRoleForSubPhase(subPhase) ?: continue
            
            // 获取角色配置
            val gameConfig = context.room.config?: throw IllegalArgumentException("[executeNightSequence] No configuration for game $gameId")
            val config = gameConfig.getDelayForRole(role)
            
            log.info("[executeNightSequence] Processing sub-phase $subPhase for role $role in game $gameId")
            
            // 发送 OPEN_EYES 事件
            broadcastOpenEyes(gameId, role, context.game.dayNumber)
            
            // 检查角色是否存活
            val isRoleAlive = context.alivePlayers.any { it.role == role }
            
            if (isRoleAlive) {
                // 角色存活：等待动作
                log.info("[executeNightSequence] Role $role is alive, waiting for action")
                
                val deferred = CompletableDeferred<com.werewolf.game.action.GameActionResult?>()
                pendingActions[role] = deferred
                
                // 发送动作提示给相关玩家
                context.alivePlayers.filter { it.role == role }.forEach { player ->
                    // TODO: 实现动作提示发送
                    log.info("[executeNightSequence] Sending action prompt to player ${player.userId}")
                }
                
                // 等待动作完成（带超时）
                val action = withTimeoutOrNull(config.actionWindowMs.milliseconds) {
                    deferred.await()
                }
                
                log.info("[executeNightSequence] Action received for role $role: $action")
                
                // 处理动作
                if (action != null) {
                    val handler = handlers.find { it.role == role }
                    if (handler != null) {
                        // TODO: 实现动作处理
                        log.info("[executeNightSequence] Handling action for role $role")
                    }
                }
                
                pendingActions.remove(role)
            } else {
                // 角色死亡：模拟延迟
                log.info("[executeNightSequence] Role $role is dead, simulating delay for ${config.deadRoleDelayMs}ms")
                delay(config.deadRoleDelayMs)
            }
            
            // 发送 CLOSE_EYES 事件
            broadcastCloseEyes(gameId, role, context.game.dayNumber)
            
            log.info("[executeNightSequence] Completed sub-phase $subPhase for role $role")
        }
        
        // 解析夜阶段结果 — reload fresh to pick up wolf/witch/guard actions set during this night
        val freshNightPhase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, newDayNumber).orElse(null)
        if (freshNightPhase != null) {
            log.info("[executeNightSequence] Resolving night kills for game $gameId (wolfTarget=${freshNightPhase.wolfTargetUserId})")
            resolveNightKills(context, freshNightPhase)
        }
    }

    /**
     * 内部初始化夜阶段的方法
     * 与 initNight 相同的逻辑，但在协程上下文中调用
     */
    private fun initNightInternal(gameId: Int, newDayNumber: Int, previousGuardTarget: String? = null, withWaiting: Boolean = false) {
        val context = contextLoader.load(gameId)
        val initialSubPhase = if (withWaiting) NightSubPhase.WAITING else firstSubPhase(context)

        val nightPhase = NightPhase(
            gameId = gameId,
            dayNumber = newDayNumber,
            subPhase = initialSubPhase,
            prevGuardTargetUserId = previousGuardTarget,
        )
        nightPhaseRepository.save(nightPhase)

        val game = context.game
        game.phase = GamePhase.NIGHT
        game.subPhase = null
        game.dayNumber = newDayNumber
        gameRepository.save(game)

        // Calculate audio sequence for NIGHT phase
        val audioSequence = audioService.calculatePhaseTransition(
            gameId = gameId,
            oldPhase = GamePhase.DAY_DISCUSSION, // Assuming coming from DAY
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = initialSubPhase.name,
            room = context.room,
        )

        // Send both events
        stompPublisher.broadcastGame(gameId, DomainEvent.PhaseChanged(gameId, GamePhase.NIGHT, initialSubPhase.name))
        stompPublisher.broadcastGame(gameId, DomainEvent.AudioSequence(gameId, audioSequence))

        if (withWaiting) {
            nightWaitingScheduler.scheduleAdvance(gameId)
        }
    }

    /**
     * 广播 OPEN_EYES 事件
     */
    private fun broadcastOpenEyes(gameId: Int, role: PlayerRole, nightNumber: Int) {
        val event = DomainEvent.OpenEyes(gameId, role, GamePhase.NIGHT, nightNumber)
        stompPublisher.broadcastGame(gameId, event)
        log.info("[broadcastOpenEyes] Broadcasting OPEN_EYES for game $gameId, role $role")
    }

    /**
     * 广播 CLOSE_EYES 事件
     */
    private fun broadcastCloseEyes(gameId: Int, role: PlayerRole, nightNumber: Int) {
        val event = DomainEvent.CloseEyes(gameId, role, GamePhase.NIGHT, nightNumber)
        stompPublisher.broadcastGame(gameId, event)
        log.info("[broadcastCloseEyes] Broadcasting CLOSE_EYES for game $gameId, role $role")
    }
}
