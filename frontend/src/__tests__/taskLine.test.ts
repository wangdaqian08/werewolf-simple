/** TaskLine: one 单局任务 — what it asks, the 戰績分 it pays, and (after the game) whether it was done. */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskLine from '@/components/TaskLine.vue'

const line = (done: boolean | null) =>
  mount(TaskLine, { props: { task: { code: 'SEER_FIND_TWO_WOLVES', score: 10, done } } })

describe('TaskLine', () => {
  it('names the task and the score it pays', () => {
    const w = line(null)
    expect(w.get('[data-testid="task-name"]').text()).toBe('查驗到至少 2 隻狼人')
    expect(w.get('[data-testid="task-score"]').text()).toBe('+10 戰績分')
    expect(w.get('[data-testid="task-line"]').attributes('data-task')).toBe('SEER_FIND_TWO_WOLVES')
  })

  it('shows no outcome while the game is running', () => {
    const w = line(null)
    expect(w.find('[data-testid="task-status"]').exists()).toBe(false)
    expect(w.get('[data-testid="task-line"]').attributes('data-done')).toBe('pending')
  })

  it('says done or not done once settled', () => {
    expect(line(true).get('[data-testid="task-status"]').text()).toBe('已完成')
    expect(line(true).get('[data-testid="task-line"]').attributes('data-done')).toBe('true')
    expect(line(false).get('[data-testid="task-status"]').text()).toBe('未完成')
    expect(line(false).get('[data-testid="task-line"]').attributes('data-done')).toBe('false')
  })

  it('every task code has a name in both languages', async () => {
    const zh = (await import('@/i18n/locales/zh')).default as unknown as {
      task: { name: Record<string, string> }
    }
    const en = (await import('@/i18n/locales/en')).default as unknown as {
      task: { name: Record<string, string> }
    }
    const codes = [
      'SURVIVE_TO_DAY3',
      'HOLD_BADGE',
      'VOTE_WOLF_TWICE',
      'VILLAGER_SURVIVE_WIN',
      'SEER_FIND_TWO_WOLVES',
      'WITCH_POISON_WOLF',
      'WITCH_SAVE_GOOD',
      'GUARD_BLOCK_KILL',
      'WOLF_NEVER_EXILED',
      'WOLF_WIN_ALIVE',
      'WOLF_FIRST_NIGHT_GOD',
    ]
    for (const c of codes) {
      expect(zh.task.name[c], c).toBeTruthy()
      expect(en.task.name[c], c).toBeTruthy()
    }
  })
})
