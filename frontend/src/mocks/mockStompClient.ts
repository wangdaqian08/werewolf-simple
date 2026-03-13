import type { IMessage } from '@stomp/stompjs'

type MsgCallback = (msg: IMessage) => void

// Payload can be a static value or a lazy function evaluated at fire time.
type PayloadFn = () => unknown

interface ScheduledEvent {
  delayMs: number
  topic: string
  payload: unknown | PayloadFn
}

/**
 * Minimal fake STOMP client that mimics @stomp/stompjs Client.
 * Events registered via scheduleEvent() fire relative to activate(),
 * so they always arrive AFTER the view has subscribed.
 *
 * Pass a function as payload to evaluate it lazily at fire time (useful
 * when the payload depends on state that changes between schedule and fire).
 */
export class MockStompClient {
  onConnect: (() => void) | undefined
  onStompError: ((frame: unknown) => void) | undefined

  private subs: Map<string, MsgCallback[]> = new Map()
  private scheduled: ScheduledEvent[] = []

  scheduleEvent(delayMs: number, topic: string, payload: unknown | PayloadFn) {
    this.scheduled.push({ delayMs, topic, payload })
  }

  activate() {
    // Call onConnect first (gives the view time to subscribe),
    // then fire scheduled events relative to this moment.
    setTimeout(() => {
      this.onConnect?.()
      for (const event of this.scheduled) {
        setTimeout(() => {
          const resolved =
            typeof event.payload === 'function' ? (event.payload as PayloadFn)() : event.payload
          this.push(event.topic, resolved)
        }, event.delayMs)
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

  /** Immediately deliver a message (alias for push; useful for debug endpoints). */
  fireNow(topic: string, payload: unknown) {
    this.push(topic, payload)
  }
}

// Singleton instance used across the app in mock mode
export const mockStompClient = new MockStompClient()
