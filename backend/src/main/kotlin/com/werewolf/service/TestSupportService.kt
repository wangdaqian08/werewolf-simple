package com.werewolf.service

import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.CreditTxType
import com.werewolf.model.GamePhase
import com.werewolf.repository.CreditTransactionRepository
import com.werewolf.repository.EliminationHistoryRepository
import com.werewolf.repository.GameEventRepository
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.GameSettlementRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.repository.PerkActivationRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.SheriffCandidateRepository
import com.werewolf.repository.SheriffElectionRepository
import com.werewolf.repository.VoteRepository
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * e2e-only data cleanup for integration test runs. Deletes every row a test
 * room left behind (room, players, games and all game-scoped children) so a
 * long-lived locally-reused backend doesn't accumulate state between runs.
 * Users and wallets are deliberately KEPT — guest identities ('Host', bots)
 * are shared across rooms within a session, and purchase transactions with
 * no game/activation linkage are wallet history, not game state.
 *
 * Gated to the e2e/test profiles (never alongside prod, and no deployment
 * runs either) in the spirit of the DevAuthController hardening — see
 * TestSupportControllerProfileTest. The `test` leg lets the backend
 * integration suite exercise the cascade inside its ordinary cached "test"
 * context: activating the e2e profile there would mix application-e2e.yml's
 * H2 driver settings with CI's Postgres SPRING_DATASOURCE_URL override and
 * the context fails at dialect resolution.
 */
@Service
@Profile("(e2e | test) & !prod")
class TestSupportService(
    private val roomRepository: RoomRepository,
    private val roomPlayerRepository: RoomPlayerRepository,
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val nightPhaseRepository: NightPhaseRepository,
    private val sheriffElectionRepository: SheriffElectionRepository,
    private val sheriffCandidateRepository: SheriffCandidateRepository,
    private val voteRepository: VoteRepository,
    private val gameEventRepository: GameEventRepository,
    private val eliminationHistoryRepository: EliminationHistoryRepository,
    private val perkActivationRepository: PerkActivationRepository,
    private val creditTransactionRepository: CreditTransactionRepository,
    private val gameSettlementRepository: GameSettlementRepository,
    private val nightOrchestrator: NightOrchestrator,
    private val walletService: WalletService,
    private val entityManager: EntityManager,
) {

    private val log = LoggerFactory.getLogger(TestSupportService::class.java)

    /**
     * Delete every room matching [roomCode] (codes recycle, so a test code
     * can map to several finished rooms) together with its full game graph,
     * children first. Returns per-table deletion counts, or null when no
     * room matches — the controller maps that to 404.
     */
    @Transactional
    fun deleteRoomCascade(roomCode: String): Map<String, Int>? {
        val rooms = roomRepository.findAllByRoomCode(roomCode)
        if (rooms.isEmpty()) return null

        val counts = linkedMapOf<String, Int>()
        fun add(table: String, n: Long) {
            counts[table] = (counts[table] ?: 0) + n.toInt()
        }

        for (room in rooms) {
            val roomId = room.roomId ?: continue
            val games = gameRepository.findByRoomId(roomId)
            val gameIds = games.mapNotNull { it.gameId }
            val activationIds = perkActivationRepository.findByRoomId(roomId).mapNotNull { it.id }
            gameIds.forEach { nightOrchestrator.cancelNightPhase(it) }

            // Children first — the e2e H2 schema has no FK constraints, but
            // the same order must hold on a Flyway-provisioned Postgres.
            if (activationIds.isNotEmpty()) {
                add(
                    "credit_transactions",
                    creditTransactionRepository.deleteByPerkActivationIdIn(activationIds),
                )
            }
            if (gameIds.isNotEmpty()) {
                add("credit_transactions", creditTransactionRepository.deleteByGameIdIn(gameIds))
                add("game_settlements", gameSettlementRepository.deleteByGameIdIn(gameIds))
                add("votes", voteRepository.deleteByGameIdIn(gameIds))
                add("game_events", gameEventRepository.deleteByGameIdIn(gameIds))
                add("elimination_history", eliminationHistoryRepository.deleteByGameIdIn(gameIds))
                val electionIds = sheriffElectionRepository.findByGameIdIn(gameIds).mapNotNull { it.id }
                if (electionIds.isNotEmpty()) {
                    add("sheriff_candidates", sheriffCandidateRepository.deleteByElectionIdIn(electionIds))
                }
                add("sheriff_elections", sheriffElectionRepository.deleteByGameIdIn(gameIds))
                add("night_phases", nightPhaseRepository.deleteByGameIdIn(gameIds))
                add("game_players", gamePlayerRepository.deleteByGameIdIn(gameIds))
            }
            add("perk_activations", perkActivationRepository.deleteByRoomId(roomId))
            gameRepository.deleteAll(games)
            add("games", games.size.toLong())
            add("room_players", roomPlayerRepository.deleteByRoomId(roomId))
            roomRepository.delete(room)
            add("rooms", 1)
        }

        log.info("[TestSupport] deleteRoomCascade code={} -> {}", roomCode, counts)
        return counts
    }

    /** Credit [userId] [amount] credits (e2e specs that buy perks); returns the new balance. */
    @Transactional
    fun grantCredits(userId: String, amount: Int): Int {
        walletService.credit(userId, amount, CreditTxType.ADMIN_GRANT, note = "e2e")
        return walletService.balance(userId)
    }

    /**
     * Swap the dealt roles of [userIdA] and [userIdB] in [gameId] so an e2e spec can
     * pin a role onto a given player. ROLE_REVEAL only: nothing derived from the role
     * exists yet (canVote/idiotRevealed/learnedRole are role-independent defaults at
     * deal time). Perk activations are left alone; e2e rooms run with perks off.
     * Returns userId -> role after the swap, or null when the request is invalid.
     */
    @Transactional
    fun swapRoles(gameId: Int, userIdA: String, userIdB: String): Map<String, String>? {
        if (userIdA == userIdB) return null
        val game = gameRepository.findByIdForUpdate(gameId).orElse(null) ?: return null
        if (game.phase != GamePhase.ROLE_REVEAL) return null
        val a = gamePlayerRepository.findByGameIdAndUserId(gameId, userIdA).orElse(null) ?: return null
        val b = gamePlayerRepository.findByGameIdAndUserId(gameId, userIdB).orElse(null) ?: return null
        // GamePlayer.role is immutable in product code; a bulk update keeps it that way.
        val update = "UPDATE GamePlayer gp SET gp.role = :role WHERE gp.id = :id"
        entityManager.createQuery(update).setParameter("role", b.role).setParameter("id", a.id).executeUpdate()
        entityManager.createQuery(update).setParameter("role", a.role).setParameter("id", b.id).executeUpdate()
        log.info("[TestSupport] swapRoles game={} {}:{}->{} {}:{}->{}", gameId, userIdA, a.role, b.role, userIdB, b.role, a.role)
        return mapOf(userIdA to b.role.name, userIdB to a.role.name)
    }
}
