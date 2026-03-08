import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'

// In mock mode this singleton is used instead of a real WebSocket connection.
// Vite removes this dead-code branch in production builds (VITE_MOCK is not set).
// eslint-disable-next-line @typescript-eslint/no-explicit-any
let client: any = null

export function createStompClient(token: string): Client {
  if (import.meta.env.VITE_MOCK === 'true') {
    // Dynamic require pattern: module is already loaded by setupMocks() in main.ts
    // We read the singleton from the already-imported module via globalThis.
    client = (globalThis as Record<string, unknown>).__mockStompClient
    return client as Client
  }

  client = new Client({
    brokerURL: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 3000,
    onStompError: (frame) => console.error('STOMP error:', frame),
  })
  return client as Client
}

export function getStompClient(): Client | null {
  return client
}

export function disconnectStomp(): void {
  client?.deactivate()
  client = null
}

export function subscribeToTopic(
  topic: string,
  callback: (msg: IMessage) => void,
): StompSubscription | undefined {
  return client?.subscribe(topic, callback)
}
