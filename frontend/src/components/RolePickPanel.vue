<template>
  <section class="rp-panel" data-testid="role-pick-panel">
    <div class="rp-title">
      <span>{{ locked ? $t('rolePick.titleLocked') : $t('rolePick.title') }}</span>
      <span v-if="balance !== null" class="rp-balance">
        ◈ {{ balance }}
        <span v-if="view.frozenCredits > 0" class="rp-frozen" data-testid="role-pick-frozen">{{
          $t('rolePick.frozen', { n: view.frozenCredits })
        }}</span>
      </span>
    </div>
    <p v-if="!locked" class="rp-hint">{{ $t('rolePick.hint') }}</p>
    <p v-else class="rp-hint" data-testid="role-pick-locked">{{ $t('rolePick.titleLocked') }}</p>

    <div class="rp-grid">
      <button
        v-for="c in cards"
        :key="c.value"
        type="button"
        class="rp-card"
        :class="{ 'rp-sel': view.choice === c.value, 'rp-off': isDisabled(c.value) }"
        :data-testid="`role-pick-${c.value}`"
        :disabled="isDisabled(c.value)"
        @click="emit('choose', c.value)"
      >
        <span class="rp-name">{{ c.icon }} {{ $t(c.name) }}</span>
        <span class="rp-price">{{
          c.value === 'NONE' ? $t('rolePick.free') : `◈${view.prices[c.value] ?? ''}`
        }}</span>
        <span class="rp-desc">{{ $t(c.desc) }}</span>
        <span v-if="c.value !== 'NONE'" class="rp-seats">{{ reason(c.value) }}</span>
        <RouterLink
          v-if="c.value !== 'NONE' && tooPoor(c.value)"
          :to="{ name: 'account' }"
          class="rp-topup"
          :data-testid="`role-pick-topup-${c.value}`"
          @click.stop
          >{{ $t('rolePick.topUp') }}</RouterLink
        >
      </button>
    </div>

    <p v-if="paid" class="rp-note rp-note-gold" data-testid="role-pick-draw-note">
      {{
        $t('rolePick.drawNote', {
          camp: $t(`rolePick.camp${cap(paid)}`),
          n: view.seats[paid],
          price: view.prices[paid] ?? 0,
        })
      }}
    </p>
    <p
      v-if="view.choice === 'WOLF' && immunityPrice !== null"
      class="rp-note rp-note-red"
      data-testid="role-pick-immunity-hint"
    >
      {{ $t('rolePick.immunityHint', { price: immunityPrice }) }}
    </p>
  </section>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { t } from '@/i18n'
import type { PaidRolePick, RolePickChoice, RolePickView } from '@/types'

const props = defineProps<{
  view: RolePickView
  balance: number | null
  locked: boolean
  busy: boolean
  immunityPrice: number | null
}>()
const emit = defineEmits<{ choose: [choice: RolePickChoice] }>()

const cards: { value: RolePickChoice; icon: string; name: string; desc: string }[] = [
  { value: 'NONE', icon: '', name: 'rolePick.none', desc: 'rolePick.noneDesc' },
  { value: 'WOLF', icon: '🐺', name: 'rolePick.wolf', desc: 'rolePick.wolfDesc' },
  { value: 'GOD', icon: '✨', name: 'rolePick.god', desc: 'rolePick.godDesc' },
  { value: 'VILLAGER', icon: '🌾', name: 'rolePick.villager', desc: 'rolePick.villagerDesc' },
]

const paid = computed<PaidRolePick | null>(() =>
  props.view.choice && props.view.choice !== 'NONE' ? props.view.choice : null,
)
const cap = (c: PaidRolePick) => c.charAt(0) + c.slice(1).toLowerCase()

function tooPoor(c: PaidRolePick): boolean {
  const price = props.view.prices[c]
  return (
    price !== undefined &&
    props.balance !== null &&
    props.view.choice !== c &&
    price > props.balance
  )
}

function isDisabled(c: RolePickChoice): boolean {
  if (props.locked || props.busy) return true
  if (c === 'NONE') return false
  return props.view.prices[c] === undefined || props.view.seats[c] === 0 || tooPoor(c)
}

function reason(c: PaidRolePick): string {
  if (props.view.prices[c] === undefined) return t('rolePick.unavailable')
  if (props.view.seats[c] === 0) return t('rolePick.noSeats')
  if (tooPoor(c)) return t('rolePick.insufficient')
  return t('rolePick.seats', { n: props.view.seats[c] })
}
</script>

<style scoped>
.rp-panel {
  border: 1px solid var(--border-l);
  border-radius: 0.5rem;
  padding: 0.625rem;
  background: rgba(160, 120, 48, 0.05);
  margin-bottom: 0.75rem;
}
.rp-title {
  display: flex;
  justify-content: space-between;
  font-size: 0.6875rem;
  font-weight: 700;
  letter-spacing: 0.1em;
  color: var(--gold);
  margin-bottom: 0.25rem;
}
.rp-frozen {
  color: var(--muted);
  font-weight: 400;
}
.rp-hint {
  font-size: 0.65rem;
  color: var(--muted);
  line-height: 1.5;
  margin: 0 0 0.5rem;
}
.rp-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.4375rem;
}
.rp-card {
  position: relative;
  text-align: left;
  min-height: 4rem;
  padding: 0.5rem;
  background: var(--card);
  border: 1.5px solid var(--border);
  border-radius: 0.5rem;
  color: var(--text);
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}
.rp-name {
  font-family: 'Noto Serif SC', serif;
  font-weight: 700;
  font-size: 0.9375rem;
}
.rp-price {
  position: absolute;
  right: 0.5rem;
  top: 0.5rem;
  font-size: 0.6875rem;
  font-weight: 700;
  color: var(--gold);
}
.rp-desc,
.rp-seats {
  font-size: 0.625rem;
  color: var(--muted);
  line-height: 1.4;
}
.rp-sel {
  border: 2px solid var(--red);
  background: rgba(181, 37, 26, 0.05);
}
.rp-sel::after {
  content: '✓';
  position: absolute;
  right: 0.5rem;
  bottom: 0.375rem;
  color: var(--red);
  font-weight: 700;
}
.rp-off {
  opacity: 0.45;
}
.rp-sel.rp-off {
  opacity: 1;
}
.rp-topup {
  font-size: 0.625rem;
  color: var(--red);
  text-decoration: underline;
}
.rp-note {
  font-size: 0.65rem;
  line-height: 1.5;
  padding: 0.375rem 0.5rem;
  border-radius: 0.375rem;
  margin: 0.4375rem 0 0;
}
.rp-note-gold {
  background: rgba(160, 120, 48, 0.1);
  color: #6e5220;
}
.rp-note-red {
  background: rgba(181, 37, 26, 0.07);
  color: var(--red);
}
</style>
