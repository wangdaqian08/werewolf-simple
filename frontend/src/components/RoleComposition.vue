<template>
  <div class="composition-card" data-testid="role-composition">
    <div class="composition-label">角色组成 / Role Composition</div>
    <div class="composition-row">
      <div
        v-for="chip in chips"
        :key="chip.id"
        :class="['composition-chip', isWolfRole(chip.id) ? 'chip-wolf' : 'chip-default']"
        :data-role="chip.id"
        :data-count="chip.count"
      >
        <span class="chip-emoji">{{ chip.emoji }}</span>
        <span class="chip-name">{{ chip.nameZh }}</span>
        <span class="chip-count">×{{ chip.count }}</span>
      </div>
    </div>
    <div class="composition-total" data-testid="composition-total">
      共 {{ totalRoles }} / {{ totalPlayers }} 座位（含房主 · incl. host）
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { ROLE_DEFINITIONS, isWolfRole, roleDefinition } from '@/utils/roleDefinitions'

const props = defineProps<{
  totalPlayers: number
  wolfCount: number
  roles: string[]
}>()

const GOD_IDS = new Set(['SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT'])

const chips = computed(() => {
  const enabledGods = props.roles.filter((r) => GOD_IDS.has(r))
  const villagerCount = props.totalPlayers - props.wolfCount - enabledGods.length

  const out: Array<{ id: string; nameZh: string; emoji: string; count: number }> = []

  // 白狼王 takes one of the wolfCount seats.
  const king = props.roles.includes('WHITE_WOLF_KING')
    ? roleDefinition('WHITE_WOLF_KING')
    : undefined
  const wolf = roleDefinition('WEREWOLF')
  const plainWolves = props.wolfCount - (king ? 1 : 0)
  if (wolf && plainWolves > 0)
    out.push({ id: wolf.id, nameZh: wolf.nameZh, emoji: wolf.emoji, count: plainWolves })
  if (king) out.push({ id: king.id, nameZh: king.nameZh, emoji: king.emoji, count: 1 })

  for (const def of ROLE_DEFINITIONS) {
    if (GOD_IDS.has(def.id) && enabledGods.includes(def.id)) {
      out.push({ id: def.id, nameZh: def.nameZh, emoji: def.emoji, count: 1 })
    }
  }

  const villager = roleDefinition('VILLAGER')
  if (villager && villagerCount > 0) {
    out.push({
      id: villager.id,
      nameZh: villager.nameZh,
      emoji: villager.emoji,
      count: villagerCount,
    })
  }

  return out
})

const totalRoles = computed(() => chips.value.reduce((s, c) => s + c.count, 0))
</script>

<style scoped>
.composition-card {
  background: var(--card);
  border: 1px solid var(--border-l);
  border-radius: 0.5rem;
  padding: 0.75rem 0.875rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.composition-label {
  font-family: 'Noto Serif SC', serif;
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--muted);
  letter-spacing: 0.04em;
}

.composition-row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
}

.composition-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.3125rem;
  padding: 0.25rem 0.5rem;
  border-radius: 999px;
  border: 1px solid var(--border-l);
  background: var(--paper);
  font-size: 0.8125rem;
  color: var(--text);
}

.composition-chip.chip-wolf {
  background: rgba(181, 37, 26, 0.08);
  border-color: rgba(181, 37, 26, 0.25);
  color: var(--red);
}

.chip-emoji {
  font-size: 1rem;
  line-height: 1;
}

.chip-name {
  font-family: 'Noto Sans SC', sans-serif;
}

.chip-count {
  font-family: 'Noto Serif SC', serif;
  font-weight: 700;
  font-size: 0.875rem;
}

.composition-total {
  font-size: 0.75rem;
  color: var(--muted);
  text-align: right;
}
</style>
