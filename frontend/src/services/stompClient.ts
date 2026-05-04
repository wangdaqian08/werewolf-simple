import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

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

  // Use SockJS for better browser compatibility and fallback
  // Vite proxy handles the WebSocket connection: /ws -> ws://localhost:8080/ws
  client = new Client({
    webSocketFactory: () => {
      return new SockJS('/ws')
    },
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      console.log('[stompClient] connected ts=' + Date.now())
    },
    onDisconnect: () => {
      console.warn('[stompClient] disconnected ts=' + Date.now())
    },
    onWebSocketClose: (evt) => {
      const tag = evt.wasClean ? 'close-clean' : 'close-DIRTY'
      console.warn(
        `[stompClient] ${tag} code=${evt.code} reason=${evt.reason} ts=${Date.now()}`,
      )
    },
    onStompError: (frame) => {
      console.error('[stompClient] STOMP错误:', frame)
    },
    onWebSocketError: (evt) => {
      console.error('[stompClient] WebSocket错误:', evt)
    },
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
