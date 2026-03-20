---
id: ADR-001
title: OAuth Authentication (Google + WeChat)
status: accepted
---

## Context

The game targets Chinese users (WeChat) and international users (Google). Players need an identity to join rooms and
receive private game state. No email/password flow is needed — the game is casual and session-scoped.

## Decision

Use **Google OAuth** and **WeChat OAuth** as the only login methods. No nickname-only or device-UUID flow.

- `POST /api/auth/google` — exchange Google auth code → upsert user → issue JWT
- `POST /api/auth/wechat` — exchange WeChat auth code → upsert user → issue JWT

JWT payload: `{ userId, nickname, avatarUrl }`. Expiry: **2 hours** (covers a full game session).

`userId` is provider-prefixed: `google:<sub>` or `wechat:<openid>` — unique across providers without a separate mapping
table.

JWT is attached as `Authorization: Bearer <token>` on all HTTP requests and as a STOMP connect header.

## Dev override

`DevAuthController` (active only under `@Profile("dev")`) issues a JWT directly given a userId + nickname, bypassing
OAuth. This allows local testing without a real OAuth flow.

## Consequences

- No guest/anonymous play — every player must log in
- WeChat OAuth requires a registered WeChat Open Platform app (China infra dependency)
- Short JWT expiry means no persistent sessions across days — acceptable for a party game
