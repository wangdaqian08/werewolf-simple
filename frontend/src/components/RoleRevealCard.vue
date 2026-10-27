<template>
  <div class="reveal-wrap">
    <!-- Header -->
    <div class="reveal-header">
      <div class="reveal-phase">{{ $t('reveal.start') }}</div>
      <div class="reveal-subtitle">
        {{ revealed ? $t('reveal.yourRole') : $t('reveal.assigned') }}
      </div>
    </div>

    <!-- Unrevealed card -->
    <div v-if="!revealed" class="role-card mystery-card">
      <div class="mystery-glyph">?</div>
      <div class="mystery-title">{{ $t('reveal.mysteryTitle') }}</div>
      <div class="mystery-hint">{{ $t('reveal.hint') }}</div>
    </div>

    <!-- Revealed card -->
    <div v-else class="role-card">
      <div class="role-emoji">{{ meta.emoji }}</div>
      <div class="role-name-zh">{{ roleName(role) }}</div>
      <div :class="`role-label-${meta.team}`" class="role-label">{{ roleTag(role) }}</div>
      <p class="role-desc" data-testid="role-reveal-desc">{{ roleDescription(role) }}</p>

      <!-- Werewolf teammates -->
      <div v-if="teammates.length > 0" class="role-teammates">
        {{ $t('reveal.teammates')
        }}<span v-for="(name, i) in teammates" :key="name" class="teammate-name"
          >{{ name }}<template v-if="i < teammates.length - 1"> · </template></span
        >
      </div>
    </div>

    <!-- Buttons -->
    <template v-if="!revealed">
      <button class="btn btn-gold" data-testid="reveal-role-btn" @click="$emit('reveal')">
        {{ $t('reveal.reveal') }}
      </button>
    </template>
    <template v-else>
      <button class="btn btn-primary" data-testid="confirm-role-btn" @click="$emit('confirm')">
        {{ $t('reveal.gotIt') }}
      </button>
      <button class="btn btn-secondary hide-btn" @click="$emit('hide')">
        {{ $t('reveal.hide') }}
      </button>
    </template>

    <!-- Footer label -->
    <div class="reveal-footer-label">
      {{
        revealed
          ? $t('reveal.footer', { tag: roleTag(role), name: roleName(role) })
          : $t('reveal.footerHidden')
      }}
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import type { PlayerRole } from '@/types'
import { roleDescription, roleName, roleTag } from '@/utils/roleDefinitions'

const props = withDefaults(
  defineProps<{
    role: PlayerRole
    teammates?: string[]
    revealed?: boolean
  }>(),
  { teammates: () => [], revealed: false },
)

defineEmits<{ confirm: []; reveal: []; hide: [] }>()

const teammates = computed(() => props.teammates)

interface RoleMeta {
  emoji: string
  team: 'wolf' | 'village' | 'special'
}

const ROLE_META: Record<PlayerRole, RoleMeta> = {
  WEREWOLF: {
    emoji: '🐺',
    team: 'wolf',
  },
  VILLAGER: {
    emoji: '🌾',
    team: 'village',
  },
  SEER: {
    emoji: '🔭',
    team: 'special',
  },
  WITCH: {
    emoji: '🔮',
    team: 'special',
  },
  HUNTER: {
    emoji: '🏹',
    team: 'special',
  },
  GUARD: {
    emoji: '🛡️',
    team: 'special',
  },
  IDIOT: {
    emoji: '🃏',
    team: 'special',
  },
  WHITE_WOLF_KING: {
    emoji: '👑',
    team: 'wolf',
  },
  BLACK_WOLF_KING: {
    emoji: '🖤',
    team: 'wolf',
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

/* Mystery card */
.mystery-card {
  justify-content: center;
}

.mystery-glyph {
  font-family: 'Noto Serif SC', serif;
  font-size: 6rem;
  font-weight: 700;
  color: var(--muted);
  line-height: 1;
  margin-bottom: 1.5rem;
}

.mystery-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--text);
}

.mystery-hint {
  font-size: 0.8125rem;
  color: var(--muted);
  margin-top: 0.25rem;
}

/* Button — full width */
.btn {
  width: 100%;
  max-width: 320px;
}

.hide-btn {
  margin-top: -0.25rem;
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
