<template>
  <div class="reveal-wrap">
    <!-- Header -->
    <div class="reveal-header">
      <div class="reveal-phase">游戏开始 · GAME START</div>
      <div class="reveal-subtitle">你的身份 / Your Role</div>
    </div>

    <!-- Card -->
    <div class="role-card">
      <div class="role-emoji">{{ meta.emoji }}</div>
      <div class="role-name-zh">{{ meta.nameZh }}</div>
      <div :class="`role-label-${meta.team}`" class="role-label">{{ meta.nameEn }}</div>
      <p class="role-desc">{{ meta.description }}</p>

      <!-- Werewolf teammates -->
      <div v-if="teammates.length > 0" class="role-teammates">
        你的队友是：<span v-for="(name, i) in teammates" :key="name" class="teammate-name"
          >{{ name }}<template v-if="i < teammates.length - 1"> · </template></span
        >
      </div>
    </div>

    <!-- Confirm button -->
    <button class="btn btn-primary" @click="$emit('confirm')">知道了 / Got it</button>

    <!-- Footer label -->
    <div class="reveal-footer-label">ROLE — {{ meta.nameEn }} {{ meta.nameZh }}</div>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import type { PlayerRole } from '@/types'

const props = withDefaults(
  defineProps<{
    role: PlayerRole
    teammates?: string[]
  }>(),
  { teammates: () => [] },
)

defineEmits<{ confirm: [] }>()

const teammates = computed(() => props.teammates)

interface RoleMeta {
  nameZh: string
  nameEn: string
  emoji: string
  team: 'wolf' | 'village' | 'special'
  description: string
}

const ROLE_META: Record<PlayerRole, RoleMeta> = {
  WEREWOLF: {
    nameZh: '狼人',
    nameEn: 'WEREWOLF',
    emoji: '🐺',
    team: 'wolf',
    description: '每晚与狼队商议，袭击一名村民。',
  },
  VILLAGER: {
    nameZh: '村民',
    nameEn: 'VILLAGER',
    emoji: '🌾',
    team: 'village',
    description: '通过讨论和投票找出狼人，保护村庄。你没有特殊技能。',
  },
  SEER: {
    nameZh: '预言家',
    nameEn: 'SEER',
    emoji: '🔭',
    team: 'special',
    description: '每晚可查验一名玩家，得知其是否为狼人。',
  },
  WITCH: {
    nameZh: '女巫',
    nameEn: 'WITCH',
    emoji: '🔮',
    team: 'special',
    description: '拥有一瓶解药和一瓶毒药，各可使用一次。',
  },
  HUNTER: {
    nameZh: '猎人',
    nameEn: 'HUNTER',
    emoji: '🏹',
    team: 'special',
    description: '死亡时可开枪带走一名玩家（被女巫毒死时无法开枪）。',
  },
  GUARD: {
    nameZh: '守卫',
    nameEn: 'GUARD',
    emoji: '🛡️',
    team: 'special',
    description: '每晚保护一名玩家免受狼人袭击，不可连续守护同一人。',
  },
  IDIOT: {
    nameZh: '白痴',
    nameEn: 'IDIOT',
    emoji: '🃏',
    team: 'special',
    description: '被投票驱逐时揭示出白痴身份，可免于出局（但失去投票权）。',
  },
}

const meta = computed(() => ROLE_META[props.role])
</script>

<style scoped>
.reveal-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-height: 100dvh;
  background: var(--bg);
  padding: 5.5rem 1.5rem 2rem;
  gap: 1.25rem;
}

/* Header */
.reveal-header {
  text-align: center;
}

.reveal-phase {
  font-size: 0.625rem;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: var(--muted);
  margin-bottom: 0.25rem;
}

.reveal-subtitle {
  font-size: 0.875rem;
  color: var(--muted);
}

/* Role card */
.role-card {
  background: var(--card);
  border: 1px solid var(--border-l);
  border-radius: 1rem;
  padding: 3.5rem 1.5rem 3rem;
  width: 100%;
  max-width: 320px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  text-align: center;
}

.role-emoji {
  font-size: 5rem;
  line-height: 1;
  margin-bottom: 1.25rem;
}

.role-name-zh {
  font-family: 'Noto Serif SC', serif;
  font-size: 2rem;
  font-weight: 700;
  color: var(--text);
  line-height: 1.1;
}

.role-label {
  font-size: 0.625rem;
  letter-spacing: 0.2em;
  font-weight: 600;
  margin-bottom: 1rem;
}

.role-label-wolf {
  color: var(--red);
}
.role-label-village {
  color: var(--green);
}
.role-label-special {
  color: var(--red);
}

.role-desc {
  font-size: 0.8125rem;
  color: var(--muted);
  line-height: 1.6;
  margin: 0;
}

.role-teammates {
  font-size: 0.8125rem;
  color: var(--muted);
  margin-top: 0.25rem;
}

.teammate-name {
  color: var(--red);
  font-weight: 500;
}

/* Button — full width */
.btn {
  width: 100%;
  max-width: 320px;
}

/* Footer label */
.reveal-footer-label {
  font-size: 0.5625rem;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: var(--muted);
  opacity: 0.6;
  margin-top: auto;
}
</style>
