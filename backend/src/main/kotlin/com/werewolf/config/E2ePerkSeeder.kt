package com.werewolf.config

import com.werewolf.model.Perk
import com.werewolf.repository.PerkRepository
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * The e2e profile runs on H2 with Flyway disabled, so the perk catalog that
 * V19 (NIGHT1_IMMUNITY) and V31 (role-pick cards) seed in real databases is
 * empty. Mirror those rows here so real-backend specs can buy perks.
 */
@Component
@Profile("e2e & !prod")
class E2ePerkSeeder(private val perks: PerkRepository) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        CATALOG.filterNot { perks.existsById(it.perkCode) }.forEach { perks.save(it) }
    }

    companion object {
        val CATALOG = listOf(
            Perk("NIGHT1_IMMUNITY", "首夜免死", "若狼人在第一夜袭击你且你不是狼人，袭击失败（与守卫/女巫救人无法区分）。若你被分配到狼人角色，该道具作废且不退款。", 30),
            Perk("ROLE_PICK_WOLF", "点将卡·狼人", "发牌时优先进入狼人阵营；想当狼的人多于名额时随机抽签。未如愿或游戏取消，积分在游戏结束时退回。", 20),
            Perk("ROLE_PICK_GOD", "点将卡·神", "发牌时优先成为神职；想当神的人多于名额时随机抽签。未如愿或游戏取消，积分在游戏结束时退回。", 20),
            Perk("ROLE_PICK_VILLAGER", "点将卡·村民", "发牌时优先成为村民；想当村民的人多于名额时随机抽签。未如愿或游戏取消，积分在游戏结束时退回。", 20),
        )
    }
}
