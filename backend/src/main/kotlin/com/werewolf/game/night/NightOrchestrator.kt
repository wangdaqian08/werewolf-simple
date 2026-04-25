package com.werewolf.game.night

import com.werewolf.audio.RoleRegistry
import com.werewolf.config.GameTimingProperties
import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.phase.HardModeCounterplay
import com.werewolf.game.phase.WinCheckTrigger
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.game.role.RoleHandler
import com.werewolf.game.role.WerewolfHandler
import com.werewolf.model.*
import com.werewolf.repository.EliminationHistoryRepository
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.service.GameContextLoader
import com.werewolf.service.StompPublisher
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

@Service
class NightOrchestrator(
    private val handlers: List<RoleHandler>,
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val nightPhaseRepository: NightPhaseRepository,
    private val eliminationHistoryRepository: EliminationHistoryRepository,
    private val winConditionChecker: WinConditionChecker,
    private val stompPublisher: StompPublisher,
    private val contextLoader: GameContextLoader,
    private val audioService: com.werewolf.service.AudioService,
    private val coroutineScope: CoroutineScope,
    private val actionLogService: com.werewolf.service.ActionLogService,
    private val timing: GameTimingProperties,
) {
    private val log = LoggerFactory.getLogger(NightOrchestrator::class.java)

    companion object {
        /** Default time in WAITING sub-phase before wolves open eyes (sheriff badge-handover window).
         *  Overridable via `werewolf.timing.waiting-delay-ms`. */
        private const val DEFAULT_WAITING_DELAY_MS = 5_000L
        /** Default time for night init audio (goes_dark_close_eyes ~2s + wolf_howl ~5s) to play before
         *  role audio starts. Overridable via `werewolf.timing.night-init-audio-delay-ms`. */
        private const val DEFAULT_NIGHT_INIT_AUDIO_DELAY_MS = 8_000L
    }

    private val waitingDelayMs: Long get() = timing.waitingDelayMs ?: DEFAULT_WAITING_DELAY_MS
    private val nightInitAudioDelayMs: Long get() = timing.nightInitAudioDelayMs ?: DEFAULT_NIGHT_INIT_AUDIO_DELAY_MS

    // One active night coroutine per game.
    private val activeNightJobs = ConcurrentHashMap<Int, Job>()

    // One pending action deferred per game — unblocked by submitAction(gameId).
    private val pendingActions = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    // If submitAction fires before the coroutine has created the next deferred (race
    // between the action arriving and the role loop reaching its await), stash the
    // signal here. The next deferred created for this gameId consumes it and
    // completes immediately instead of waiting. Without this, the race would drop
    // the action and the night would hang forever.
    private val queuedActionSignals = ConcurrentHashMap<Int, Boolean>()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Ordered list of night sub-phases for this game.
     */
    fun nightSequence(context: GameContext): List<NightSubPhase> {
        val activeRoles = mutableSetOf(PlayerRole.WEREWOLF)
        if (context.room.hasSeer) activeRoles.add(PlayerRole.SEER)
        if (context.room.hasWitch) activeRoles.add(PlayerRole.WITCH)
        if (context.room.hasGuard) activeRoles.add(PlayerRole.GUARD)

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
     *
     * The DB setup and initial broadcast happen synchronously (within the caller's transaction).
     * The night role-loop coroutine is launched after the transaction commits, so it always
     * sees a fully-committed game state.
     */
    @Transactional
    fun initNight(gameId: Int, newDayNumber: Int, previousGuardTarget: String? = null, withWaiting: Boolean = false) {
        requireNotNull(contextLoader.load(gameId).room.config) {
            "[initNight] game=$gameId: Room.config must not be null — set GameConfig before starting night phase"
        }

        // Synchronous init: saves DB records (NightPhase + Game).
        // STOMP broadcasts are deferred to afterCommit so the frontend's getGameState
        // sees committed data (game.phase=NIGHT, nightPhase record exists).
        initNightInternal(gameId, newDayNumber, previousGuardTarget, withWaiting)

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    broadcastNightInit(gameId, withWaiting)
                    launchRoleLoop(gameId, newDayNumber, withWaiting)
                }
            })
        } else {
            // No transaction (e.g., tests or already in a coroutine context)
            broadcastNightInit(gameId, withWaiting)
            launchRoleLoop(gameId, newDayNumber, withWaiting)
        }
    }

    /**
     * Signal that the current night action for [gameId] is complete.
     * Unblocks the awaiting coroutine so it advances to the next sub-phase.
     * Safe to call multiple times — extra calls are no-ops.
     *
     * CRITICAL: the actual release of the coroutine is deferred to the caller's
     * `afterCommit` hook. Callers (e.g. GameActionDispatcher.SEER_CHECK handler)
     * run inside an @Transactional that writes role-specific state like
     * seerCheckedUserId / witchPoisonTargetUserId. The role-loop coroutine runs
     * on a separate thread and, as soon as it wakes, re-reads NightPhase from a
     * new transaction and calls save() with the whole entity — a JPA MERGE.
     *
     * If the coroutine wakes before the caller's tx has committed, its read
     * returns the pre-commit state (role-action fields null), and its save
     * writes that back — wiping the field just written by the caller. Surface
     * symptom: SEER_CHECK completes, sub-phase advances to SEER_RESULT, but
     * seerCheckedUserId is silently null on the next getGameState call, so the
     * frontend can't render the result UI and the game hangs. Deferring the
     * completion to afterCommit guarantees the coroutine's next read sees the
     * just-committed state.
     */
    fun submitAction(gameId: Int) {
        val thread = Thread.currentThread().name
        val txActive = TransactionSynchronizationManager.isActualTransactionActive()
        // [race] Two critical facts for diagnosing the NightPhase-field-wipe race:
        //   1. Which thread called submitAction — almost always the HTTP worker
        //      (e.g. `nio-8080-exec-*`). Compare with the coroutine thread
        //      (`*-dispatcher-worker-*`) on the matching `[race] read` log below.
        //   2. Whether the caller is inside an @Transactional. If yes, we MUST
        //      defer to afterCommit; releasing now would wake the coroutine
        //      before the handler's write (seerCheckedUserId, wolfTargetUserId,
        //      witchPoisonTargetUserId, guardTargetUserId) is visible to other
        //      sessions, and the coroutine's subsequent merge/save can stomp it.
        if (txActive) {
            log.info("[race] submitAction game=$gameId thread=$thread txActive=true → deferring release to afterCommit")
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    log.info("[race] afterCommit fired game=$gameId thread=${Thread.currentThread().name} → releasing coroutine (HTTP tx fully committed; field writes are now visible)")
                    releaseActionSignal(gameId)
                }

                override fun afterCompletion(status: Int) {
                    // Log rollback / mixed completion so we can tell a hang apart from a
                    // silent tx failure (e.g. constraint violation inside the handler).
                    if (status != STATUS_COMMITTED) {
                        log.warn("[race] afterCompletion game=$gameId status=$status (NOT committed) — coroutine will NOT be released from this submitAction call; game may hang unless another action is retried")
                    }
                }
            })
        } else {
            // No active transaction (tests, coroutine-internal callers, bot scripts).
            // Safe to release immediately because there's no pending write to wait for.
            log.info("[race] submitAction game=$gameId thread=$thread txActive=false → releasing immediately (no write-visibility barrier needed)")
            releaseActionSignal(gameId)
        }
    }

    private fun releaseActionSignal(gameId: Int) {
        val deferred = pendingActions[gameId]
        if (deferred != null) {
            log.info("[submitAction] Completing pending action for game $gameId")
            deferred.complete(Unit)
        } else {
            // The role loop has not yet reached its deferred.await (happens when an
            // action arrives during the brief window between subphase DB-write and
            // deferred creation, or during the initial audio-warmup delay). Queue
            // the signal so the next deferred-await for this game consumes it.
            log.info("[submitAction] No pending action for game $gameId — queuing signal for next await")
            queuedActionSignals[gameId] = true
        }
    }

    /**
     * Advance from WAITING sub-phase to the first real sub-phase (WEREWOLF_PICK).
     * Called by the debug endpoint or NightWaitingScheduler as a fallback.
     * In normal flow the night coroutine handles the WAITING delay automatically.
     */
    @Transactional
    fun advanceFromWaiting(gameId: Int) {
        val context = contextLoader.load(gameId)
        val nightPhase = context.nightPhase ?: return
        if (nightPhase.subPhase != NightSubPhase.WAITING) return // idempotent guard
        val firstRealSubPhase = firstSubPhase(context)
        nightPhase.subPhase = firstRealSubPhase
        nightPhaseRepository.save(nightPhase)

        val audioSequence = audioService.calculateNightSubPhaseTransition(
            gameId = gameId,
            oldSubPhase = NightSubPhase.WAITING,
            newSubPhase = firstRealSubPhase,
        )

        stompPublisher.broadcastGame(gameId, DomainEvent.NightSubPhaseChanged(gameId, firstRealSubPhase))
        stompPublisher.broadcastGame(gameId, DomainEvent.AudioSequence(gameId, audioSequence))
    }

    /**
     * Advance to a specific sub-phase after a scheduled delay.
     * Kept for NightWaitingScheduler.scheduleAdvance compatibility.
     */
    @Transactional
    fun advanceToSubPhase(gameId: Int, targetSubPhase: NightSubPhase?) {
        if (targetSubPhase == null) return
        val context = contextLoader.load(gameId)
        val nightPhase = context.nightPhase ?: return
        val oldSubPhase = nightPhase.subPhase
        nightPhase.subPhase = targetSubPhase
        nightPhaseRepository.save(nightPhase)

        val audioSequence = audioService.calculateNightSubPhaseTransition(
            gameId = gameId,
            oldSubPhase = oldSubPhase,
            newSubPhase = targetSubPhase,
        )

        stompPublisher.broadcastGame(gameId, DomainEvent.NightSubPhaseChanged(gameId, targetSubPhase))
        stompPublisher.broadcastGame(gameId, DomainEvent.AudioSequence(gameId, audioSequence))
    }

    /**
     * Resolve all night kills and transition to DAY_DISCUSSION (or GAME_OVER).
     */
    @Transactional
    fun resolveNightKills(context: GameContext, nightPhase: NightPhase) {
        val gameId = context.gameId
        if (nightPhase.subPhase == NightSubPhase.COMPLETE) {
            log.warn("[resolveNightKills] Night phase already COMPLETE for game $gameId, skipping double-resolution")
            return
        }
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

        for (killId in kills.distinct()) {
            gamePlayerRepository.findByGameIdAndUserId(gameId, killId).ifPresent { player ->
                player.alive = false
                gamePlayerRepository.save(player)
            }
        }

        actionLogService.recordNightDeaths(gameId, nightPhase.dayNumber, kills.distinct())

        nightPhase.subPhase = NightSubPhase.COMPLETE
        nightPhaseRepository.save(nightPhase)

        val updatedContext = contextLoader.load(gameId)
        val winner = winConditionChecker.check(
            alivePlayers = updatedContext.alivePlayers,
            mode = updatedContext.room.winCondition,
            trigger = WinCheckTrigger.POST_NIGHT,
            counterplay = buildCounterplay(updatedContext),
        )

        if (winner != null) {
            context.game.apply {
                this.winner = winner
                phase = GamePhase.GAME_OVER
                endedAt = LocalDateTime.now()
            }
            gameRepository.save(context.game)

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

            val audioSequence = audioService.calculatePhaseTransition(
                gameId = gameId,
                oldPhase = GamePhase.NIGHT,
                newPhase = GamePhase.DAY_DISCUSSION,
                oldSubPhase = null,
                newSubPhase = DaySubPhase.RESULT_HIDDEN.name,
                room = updatedContext.room,
            )

            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        try {
                            stompPublisher.broadcastGame(gameId, DomainEvent.NightResult(gameId, kills.distinct()))
                        } catch (e: Exception) {
                            log.error("[resolveNightKills] Failed to broadcast NightResult for game $gameId", e)
                        }
                        try {
                            stompPublisher.broadcastGame(
                                gameId,
                                DomainEvent.PhaseChanged(gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_HIDDEN.name)
                            )
                        } catch (e: Exception) {
                            log.error("[resolveNightKills] CRITICAL: Failed to broadcast PhaseChanged for game $gameId", e)
                        }
                        try {
                            stompPublisher.broadcastGame(gameId, DomainEvent.AudioSequence(gameId, audioSequence))
                        } catch (e: Exception) {
                            log.error("[resolveNightKills] Failed to broadcast AudioSequence for game $gameId (non-critical)", e)
                        }
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
                stompPublisher.broadcastGame(gameId, DomainEvent.NightResult(gameId, kills.distinct()))
                stompPublisher.broadcastGame(
                    gameId,
                    DomainEvent.PhaseChanged(gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_HIDDEN.name)
                )
                stompPublisher.broadcastGame(gameId, DomainEvent.AudioSequence(gameId, audioSequence))

                val activeRoles = updatedContext.alivePlayers.map { it.role }.toSet()
                handlers.filter { it.role in activeRoles }.forEach { handler ->
                    val events = handler.onDayEnter(updatedContext)
                    events.forEach { stompPublisher.broadcastGame(gameId, it) }
                }
            }
        }
    }

    /**
     * Launch the night phase coroutine. Replaces any previously running coroutine for this game.
     * Calls [initNightInternal] + [broadcastNightInit] first, then runs the role loop.
     * Used by tests — production callers should use [initNight].
     */
    fun startNightPhase(gameId: Int, newDayNumber: Int, previousGuardTarget: String? = null, withWaiting: Boolean = false): Job {
        activeNightJobs[gameId]?.cancel()

        val job = coroutineScope.launch {
            try {
                initNightInternal(gameId, newDayNumber, previousGuardTarget, withWaiting)
                broadcastNightInit(gameId, withWaiting)
                nightRoleLoop(gameId, newDayNumber, withWaiting)
            } catch (e: Exception) {
                log.error("[startNightPhase] Night phase failed for game $gameId", e)
            } finally {
                activeNightJobs.remove(gameId)
            }
        }

        activeNightJobs[gameId] = job
        log.info("[startNightPhase] Started night phase for game $gameId, day=$newDayNumber")
        return job
    }

    /**
     * Cancel the running night coroutine for [gameId].
     */
    fun cancelNightPhase(gameId: Int) {
        log.info("[cancelNightPhase] Cancelling night phase for game $gameId")
        activeNightJobs[gameId]?.cancel()
        activeNightJobs.remove(gameId)
        pendingActions.remove(gameId)
        queuedActionSignals.remove(gameId)
        (handlers.firstOrNull { it.role == PlayerRole.WEREWOLF } as? WerewolfHandler)?.resetKillLock(gameId)
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * Launch only the role loop (DB init already done by [initNight]).
     */
    private fun launchRoleLoop(gameId: Int, dayNumber: Int, withWaiting: Boolean) {
        activeNightJobs[gameId]?.cancel()
        val job = coroutineScope.launch {
            try {
                // Wait for night init audio (goes_dark_close_eyes + wolf_howl) to finish
                // before role audio starts. Without this, wolf_open_eyes AudioSequence arrives
                // immediately and overrides the init AudioSequence on the frontend.
                delay(nightInitAudioDelayMs.milliseconds)
                nightRoleLoop(gameId, dayNumber, withWaiting)
            } catch (e: Exception) {
                log.error("[nightRoleLoop] Night phase failed for game $gameId", e)
            } finally {
                activeNightJobs.remove(gameId)
            }
        }
        activeNightJobs[gameId] = job
        log.info("[launchRoleLoop] Launched role loop for game $gameId, day=$dayNumber")
    }

    /**
     * Core coroutine: iterates active roles sequentially.
     *
     * For each role:
     *   1. Broadcast open_eyes audio
     *   2. Delay [RoleDelayConfig.audioWarmupMs]
     *   3. If alive:  for each sub-phase → update DB + NightSubPhaseChanged + await action (with timeout)
     *      If dead:   set first sub-phase in DB + NightSubPhaseChanged + delay [RoleDelayConfig.deadRoleDelayMs]
     *   4. Broadcast close_eyes audio
     *   5. Delay [RoleDelayConfig.audioCooldownMs]
     *   6. If not last role: delay [RoleDelayConfig.interRoleGapMs]
     *
     * Then calls [resolveNightKills] → DAY.
     */
    private suspend fun nightRoleLoop(gameId: Int, dayNumber: Int, withWaiting: Boolean) {
        // When the game starts in WAITING (sheriff-election night), pause before wolves open eyes.
        if (withWaiting) {
            log.info("[nightRoleLoop] game=$gameId: waiting ${waitingDelayMs}ms before night sequence starts")
            delay(waitingDelayMs.milliseconds)
        }

        val context = contextLoader.load(gameId)
        val gameConfig = context.room.config
            ?: throw IllegalArgumentException("[nightRoleLoop] Room.config is null for game $gameId — set GameConfig before starting night")

        val activeRoles = buildList {
            add(PlayerRole.WEREWOLF)
            if (context.room.hasSeer) add(PlayerRole.SEER)
            if (context.room.hasWitch) add(PlayerRole.WITCH)
            if (context.room.hasGuard) add(PlayerRole.GUARD)
        }

        val activeHandlers = handlers.filter { it.role in activeRoles }
        log.info("[nightRoleLoop] game=$gameId: roles=${activeHandlers.map { it.role }}")

        for ((index, handler) in activeHandlers.withIndex()) {
            val role = handler.role
            val config = gameConfig.getDelayForRole(role)
            val isAlive = context.alivePlayers.any { it.role == role }
            val isLastRole = index == activeHandlers.lastIndex

            log.info("[nightRoleLoop] game=$gameId: role=$role alive=$isAlive")

            // 1. Open eyes — each role owns its own audio (decoupled from phase-transition
            // audio). The NIGHT_INIT_AUDIO_DELAY_MS delay above ensures the night-entry
            // sequence (goes_dark_close_eyes + wolf_howl) has finished playing client-side
            // before this role's open-eyes sequence arrives and the frontend queue resets.
            RoleRegistry.getOpenEyesAudio(role)?.let { broadcastAudio(gameId, it) }
            delay(config.audioWarmupMs.milliseconds)

            if (isAlive) {
                // Alive role: iterate each sub-phase, await player action for each.
                // NO timeout — wait indefinitely until submitAction(gameId) is called.
                // IMPORTANT: Reload nightPhase from DB before each save. Action handlers
                // (e.g. SeerHandler) modify the same DB record via a different JPA entity.
                // Reusing a stale entity would overwrite their changes (e.g. seerCheckedUserId).
                for (subPhase in handler.nightSubPhases()) {
                    // [race] READ — if the race is live, this read happens before the
                    // handler's HTTP tx commits and the role-action fields read as null.
                    // On the fixed path (submitAction defers to afterCommit), the read
                    // always happens AFTER commit so the fields are visible here.
                    val nightPhase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, dayNumber)
                        .orElseThrow { IllegalStateException("[nightRoleLoop] NightPhase not found for game $gameId, day $dayNumber") }
                    logNightPhaseFields("read-before-save", gameId, dayNumber, subPhase, nightPhase)

                    nightPhase.subPhase = subPhase
                    // [race] SAVE — if the read above returned null for a role-action
                    // field that should be populated, the subsequent merge stomps the
                    // DB row back to null. The "save-about-to-merge" log pairs with the
                    // preceding "read-before-save" to prove stomp vs preserve.
                    logNightPhaseFields("save-about-to-merge", gameId, dayNumber, subPhase, nightPhase)
                    nightPhaseRepository.save(nightPhase)
                    stompPublisher.broadcastGame(gameId, DomainEvent.NightSubPhaseChanged(gameId, subPhase))

                    val deferred = CompletableDeferred<Unit>()
                    pendingActions[gameId] = deferred
                    // Honor any signal that arrived while the coroutine was still
                    // setting up — without this, an action submitted between
                    // nightPhaseRepository.save and pendingActions.put would hang.
                    if (queuedActionSignals.remove(gameId) == true) {
                        deferred.complete(Unit)
                    }
                    deferred.await()
                    pendingActions.remove(gameId)

                    log.info("[nightRoleLoop] game=$gameId: sub-phase $subPhase completed")
                }
            } else {
                // Dead role: show first sub-phase in UI, wait fixed time
                val firstSubPhase = handler.nightSubPhases().firstOrNull()
                if (firstSubPhase != null) {
                    val nightPhase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, dayNumber)
                        .orElseThrow { IllegalStateException("[nightRoleLoop] NightPhase not found for game $gameId, day $dayNumber") }
                    logNightPhaseFields("read-dead-role", gameId, dayNumber, firstSubPhase, nightPhase)
                    nightPhase.subPhase = firstSubPhase
                    logNightPhaseFields("save-dead-role", gameId, dayNumber, firstSubPhase, nightPhase)
                    nightPhaseRepository.save(nightPhase)
                    stompPublisher.broadcastGame(gameId, DomainEvent.NightSubPhaseChanged(gameId, firstSubPhase))
                }
                delay(config.deadRoleDelayMs.milliseconds)
            }

            // 3. Close eyes
            RoleRegistry.getCloseEyesAudio(role)?.let { broadcastAudio(gameId, it) }
            delay(config.audioCooldownMs.milliseconds)

            // 4. Inter-role gap (not after the last role)
            if (!isLastRole) {
                delay(config.interRoleGapMs.milliseconds)
            }
        }

        // Reload fresh context + night phase to pick up all actions committed during the night
        val freshContext = contextLoader.load(gameId)
        val freshNightPhase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, dayNumber)
            .orElseThrow { IllegalStateException("[nightRoleLoop] NightPhase not found at end of night for game $gameId") }

        log.info("[nightRoleLoop] game=$gameId: resolving night kills (wolfTarget=${freshNightPhase.wolfTargetUserId})")
        resolveNightKills(freshContext, freshNightPhase)
    }

    /**
     * Initialise the NightPhase DB record and update Game to NIGHT.
     * Does NOT broadcast — call [broadcastNightInit] after the transaction commits.
     * Called synchronously by [initNight] and inside the coroutine by [startNightPhase].
     */
    private fun initNightInternal(gameId: Int, newDayNumber: Int, previousGuardTarget: String? = null, withWaiting: Boolean = false) {
        // Clear per-night locks so each new night starts fresh.
        (handlers.firstOrNull { it.role == PlayerRole.WEREWOLF } as? WerewolfHandler)?.resetKillLock(gameId)

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
    }

    /**
     * Broadcast PhaseChanged + AudioSequence for the night phase init.
     * Must be called AFTER the transaction that created the NightPhase has committed,
     * so the frontend's getGameState sees consistent DB state.
     */
    private fun broadcastNightInit(gameId: Int, withWaiting: Boolean) {
        val context = contextLoader.load(gameId)
        val initialSubPhase = if (withWaiting) NightSubPhase.WAITING else firstSubPhase(context)

        val audioSequence = audioService.calculatePhaseTransition(
            gameId = gameId,
            oldPhase = GamePhase.DAY_DISCUSSION,
            newPhase = GamePhase.NIGHT,
            oldSubPhase = null,
            newSubPhase = initialSubPhase.name,
            room = context.room,
        )

        stompPublisher.broadcastGame(gameId, DomainEvent.PhaseChanged(gameId, GamePhase.NIGHT, initialSubPhase.name))
        stompPublisher.broadcastGame(gameId, DomainEvent.AudioSequence(gameId, audioSequence))
    }

    /**
     * Broadcast a single audio file as an AudioSequence event.
     */
    /**
     * [race] Dump every role-action field on a NightPhase at a critical point in the
     * role loop (immediately after a read, and immediately before the merge/save).
     *
     * How to read these logs to diagnose the wipe-on-merge race:
     *
     *   • `read-before-save`  → values the coroutine sees on the fresh fetch. If a
     *     field expected to be populated (because a handler just wrote it) shows
     *     `null`, the handler's tx had NOT yet committed when the coroutine fetched
     *     — the race is LIVE.
     *
     *   • `save-about-to-merge` → values on the detached entity the coroutine is
     *     about to hand to `save()` (JPA MERGE copies these fields onto the managed
     *     entity, overwriting committed state). Identical to the read-log unless
     *     the coroutine itself mutated a field in between.
     *
     * On the fixed path (submitAction defers to afterCommit), `read-before-save`
     * on the sub-phase that follows a handler write (e.g. SEER_RESULT right after
     * SEER_CHECK) MUST show the written field non-null. If it ever shows null, the
     * afterCommit hook isn't actually holding the coroutine back for this path.
     */
    private fun logNightPhaseFields(
        tag: String,
        gameId: Int,
        dayNumber: Int,
        subPhase: NightSubPhase,
        np: NightPhase,
    ) {
        log.info(
            "[race] {} game={} day={} subPhase={} thread={} -> wolfTarget={} seerChecked={} seerIsWolf={} witchAntidoteUsed={} witchPoisonTarget={} guardTarget={}",
            tag,
            gameId,
            dayNumber,
            subPhase,
            Thread.currentThread().name,
            np.wolfTargetUserId,
            np.seerCheckedUserId,
            np.seerResultIsWerewolf,
            np.witchAntidoteUsed,
            np.witchPoisonTargetUserId,
            np.guardTargetUserId,
        )
    }

    private fun broadcastAudio(gameId: Int, audioFile: String) {
        val audioSequence = AudioSequence(
            id = "$gameId-${System.currentTimeMillis()}-$audioFile",
            phase = GamePhase.NIGHT,
            subPhase = "",
            audioFiles = listOf(audioFile),
            priority = 5,
            timestamp = System.currentTimeMillis(),
        )
        stompPublisher.broadcastGame(gameId, DomainEvent.AudioSequence(gameId, audioSequence))
    }

    /**
     * Computes HARD_MODE counterplay flags from the just-loaded GameContext.
     * Read-your-writes on EliminationHistory is guaranteed because callers run inside
     * the same @Transactional where any hunter-shot row was just saved — JPA auto-flushes
     * before the query.
     */
    private fun buildCounterplay(context: GameContext): HardModeCounterplay {
        val alive = context.alivePlayers
        val hasGuard = alive.any { it.role == PlayerRole.GUARD }

        val hasWitchWithPotions = run {
            if (alive.none { it.role == PlayerRole.WITCH }) return@run false
            val antidoteUnused = context.allNightPhases.none { it.witchAntidoteUsed }
            val poisonUnused = context.allNightPhases.none { it.witchPoisonTargetUserId != null }
            antidoteUnused || poisonUnused
        }

        val hasHunterWithBullet = run {
            if (alive.none { it.role == PlayerRole.HUNTER }) return@run false
            val hunterHasShot = eliminationHistoryRepository
                .findByGameId(context.gameId)
                .any { it.hunterShotUserId != null }
            !hunterHasShot
        }

        return HardModeCounterplay(hasGuard, hasWitchWithPotions, hasHunterWithBullet)
    }
}
