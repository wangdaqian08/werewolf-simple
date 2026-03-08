import type { IMessage } from '@stomp/stompjs'

type MsgCallback = (msg: IMessage) => void

interface ScheduledEvent {
  delayMs: number
  topic: string
  payload: unknown
}

/**
 * Minimal fake STOMP client that mimics @stomp/stompjs Client.
 * Events registered via scheduleEvent() fire relative to activate(),
 * so they always arrive AFTER the view has subscribed.
 */
export class MockStompClient {
  onConnect: (() => void) | undefined
  onStompError: ((frame: unknown) => void) | undefined

  private subs: Map<string, MsgCallback[]> = new Map()
  private scheduled: ScheduledEvent[] = []

  /**
   * Register a fake STOMP message to push after activate().
   * Call this from mocks/index.ts — all event data stays in data.ts.
   */
  scheduleEvent(delayMs: number, topic: string, payload: unknown) {
    this.scheduled.push({ delayMs, topic, payload })
  }

  activate() {
    // Call onConnect first (gives the view time to subscribe),
    // then fire scheduled events relative to this moment.
    setTimeout(() => {
      this.onConnect?.()
      for (const event of this.scheduled) {
        setTimeout(() => this.push(event.topic, event.payload), event.delayMs)
      }
    }, 50)
  }

  deactivate() {
    this.subs.clear()
    this.scheduled = []
  }

  subscribe(topic: string, callback: MsgCallback) {
    const list = this.subs.get(topic) ?? []
    this.subs.set(topic, [...list, callback])
    return {
      id: topic,
      unsubscribe: () => {},
    }
  }

  /** Push a fake message to all subscribers of a topic. */
  push(topic: string, payload: unknown) {
    const fakeMsg = { body: JSON.stringify(payload) } as IMessage
    this.subs.get(topic)?.forEach((cb) => cb(fakeMsg))
  }
}

// Singleton instance used across the app in mock mode
export const mockStompClient = new MockStompClient()
