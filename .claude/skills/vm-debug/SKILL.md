---
name: vm-debug
description: Use when debugging the live werewolf-simple game on the GCP production VM — checking backend / frontend logs, querying the Postgres DB, inspecting container health, or running the repo's debugging scripts (act.sh, roles.sh, etc.) against the running server. Also use when a bug reproduces only in prod and you need evidence from the VM before editing code.
---

# VM Debug — werewolf-simple (GCP)

## Coordinates

| Field | Value |
|---|---|
| VM name | `werewolf-server` |
| Zone | `us-east1-d` |
| GCP project | `werewolf-301709` |
| External IP | `35.243.171.224` |
| Internal IP | `10.142.0.2` |
| Repo path on VM | `/opt/werewolf-simple` |

**Stack on VM:** docker compose. Three services — `postgres`, `backend` (`127.0.0.1:8080`), `frontend` (`127.0.0.1:8081`). Host Nginx terminates TLS and proxies `/api` + `/ws` to `backend`. See `deploy/README.md` and `docker-compose.yml` at the repo root.

## Step 1 — Verify gcloud auth before SSH (do this every session)

Expired gcloud creds surface as "permission denied" on SSH, not as an auth error. Check first:

```bash
gcloud auth list        # must show an ACTIVE account, not "No credentialed accounts"
gcloud config get-value project   # must print werewolf-301709
```

If either fails, STOP and ask the user to run (in their own terminal — interactive OAuth):

```bash
gcloud auth login
gcloud config set project werewolf-301709
```

Do not try to run SSH commands until this passes. Retrying a failing SSH just produces noise.

## Step 2 — Pick the right SSH shape

**Helper script (default — use this for everything):**

```bash
./scripts/vm-ssh.sh '<remote command>'
```

`scripts/vm-ssh.sh` opens an SSH ControlMaster on first call (~6s, mostly gcloud overhead) and reuses it for every subsequent call (~0.5s each, bypassing gcloud). Master persists 30 min after the last command. Without this, every gcloud-ssh invocation pays a fresh 3s+ Python startup tax.

```bash
./scripts/vm-ssh.sh 'curl -sf http://127.0.0.1:8080/api/health'
./scripts/vm-ssh.sh 'cd /opt/werewolf-simple && sudo docker compose --env-file .env.prod ps'
```

**Raw gcloud one-shot** (fall back when the helper isn't available, e.g. from a fresh checkout):

```bash
gcloud compute ssh werewolf-server --zone=us-east1-d --command="<cmd>"
```

**Interactive shell** (for poking around, multi-step debugging):

```bash
gcloud compute ssh werewolf-server --zone=us-east1-d
```

**Port-forward** (run LOCAL scripts against the remote backend — keeps `/tmp/werewolf-*.json` state files local):

```bash
# Terminal 1: forward 8080 → VM backend
gcloud compute ssh werewolf-server --zone=us-east1-d -- -L 8080:127.0.0.1:8080 -N

# Terminal 2: local scripts use the tunnel
BACKEND_BASE=http://localhost:8080/api ./scripts/act.sh STATUS --room ABCD
```

## Step 3 — Common debug commands

All commands below assume you wrap them in `./scripts/vm-ssh.sh 'cd /opt/werewolf-simple && <CMD>'` (or the raw gcloud equivalent if the helper isn't available).

**Two things every docker command needs on this VM:**

1. `sudo` — the login user is not in the `docker` group, so bare `docker ...` fails with `permission denied ... /var/run/docker.sock`.
2. `--env-file .env.prod` — compose does not auto-load that file (only `.env`). Without it `POSTGRES_USER`/`POSTGRES_DB`/`POSTGRES_PASSWORD` resolve to blank strings and commands fail or target the wrong DB.

So the real prefix is `sudo docker compose --env-file .env.prod ...`. Use `-T` on `exec` for non-interactive calls or it errors with "the input device is not a TTY".

### Health & status

```bash
curl -sf http://127.0.0.1:8080/api/health                       # {"status":"UP"}
sudo docker compose --env-file .env.prod ps                     # container states + ports
sudo docker compose --env-file .env.prod config                 # resolved compose config
```

### Backend logs

```bash
sudo docker compose --env-file .env.prod logs --tail=200 backend
sudo docker compose --env-file .env.prod logs --since=10m backend
sudo docker compose --env-file .env.prod logs backend | grep -i 'ROOM_CODE\|ERROR'
```

Do not use `-f` (follow) over `--command` — it blocks forever. Use `--since` or `--tail` for snapshots, or open an interactive shell if you really need to stream.

### Frontend logs

```bash
sudo docker compose --env-file .env.prod logs --tail=200 frontend   # nginx in the container
sudo tail -n 200 /var/log/nginx/access.log                          # host nginx (TLS + proxy)
sudo tail -n 200 /var/log/nginx/error.log
```

The frontend container serves static assets only — 404s and TLS issues show up in the **host** nginx logs, not the container log.

### Postgres

Credentials live in `/opt/werewolf-simple/.env.prod` on the VM (POSTGRES_USER / POSTGRES_PASSWORD / POSTGRES_DB — defaults to `werewolf` / `werewolf` / `werewolf`). Password is not in git.

```bash
# Ad-hoc query (non-interactive — note -T)
sudo docker compose --env-file .env.prod exec -T postgres psql -U werewolf -d werewolf \
  -c 'SELECT room_id, room_code, status, created_at FROM rooms ORDER BY room_id DESC LIMIT 10;'

# List tables
sudo docker compose --env-file .env.prod exec -T postgres psql -U werewolf -d werewolf -c '\dt'

# Interactive psql (open an SSH shell first, then run)
sudo docker compose --env-file .env.prod exec postgres psql -U werewolf -d werewolf
```

Primary-key columns are domain-prefixed, not `id`: `rooms.room_id`, `games.game_id`, `users.user_id`. The join tables (`room_players`, `game_players`) do use `id`. Full schema: `backend/src/main/resources/db/migration/V1__core.sql` and later `V*.sql`.

Tables: `users`, `rooms`, `room_players`, `games`, `game_players`, `night_phases`, `votes`, `elimination_history`, `game_events`, `sheriff_elections`, `sheriff_candidates`.

### Running the in-repo debug scripts remotely

The same `scripts/` that work locally are at `/opt/werewolf-simple/scripts/` on the VM. They honor `BACKEND_BASE` so pointing them at the in-VM backend works (no sudo needed — these are plain curl against `:8080`):

```bash
cd /opt/werewolf-simple && BACKEND_BASE=http://127.0.0.1:8080/api ./scripts/act.sh STATUS --room ABCD
cd /opt/werewolf-simple && BACKEND_BASE=http://127.0.0.1:8080/api ./scripts/roles.sh --room ABCD
```

**Caveat — dev-only endpoints:** `scripts/dev-login.sh`, `as-bot.sh`, `join-room.sh` (bot login path), and anything else that calls `POST /api/auth/dev` only work when the backend is running the **dev** Spring profile. In production (`SPRING_PROFILES_ACTIVE=prod`) those calls return 404. Confirm the active profile before assuming they'll work:

```bash
grep SPRING_PROFILES_ACTIVE /opt/werewolf-simple/.env.prod
sudo docker compose --env-file .env.prod logs backend | grep -i 'active profile' | head -3
```

If prod is active, use read-only routes (`/api/room/{id}`, `/api/game/{id}/state`) with an existing player's JWT, or the DB for forensics.

### Copying a log excerpt back to local

```bash
# Single file
gcloud compute scp werewolf-server:/opt/werewolf-simple/some.log /tmp/ --zone=us-east1-d

# Capture inline (preferred for short snippets — avoids leaving files on the VM)
gcloud compute ssh werewolf-server --zone=us-east1-d \
  --command="cd /opt/werewolf-simple && docker compose logs --tail=500 backend" > /tmp/backend-tail.log
```

## Safety rules (non-negotiable)

These commands affect live players. **Confirm with the user before running:**

- `sudo docker compose --env-file .env.prod down` / `down -v` — stops the stack; `-v` wipes the Postgres volume (all rooms / games gone)
- `sudo docker compose --env-file .env.prod restart backend` / `restart frontend` — kicks every connected player
- `sudo docker compose --env-file .env.prod up -d --build` — rebuilds and restarts (same downtime cost)
- `sudo docker system prune` — can delete images still needed by the compose stack
- Any `psql` `UPDATE` / `DELETE` / `TRUNCATE` / `DROP` — mutates prod game state
- `git pull` / `git reset` inside `/opt/werewolf-simple` — changes what the next rebuild deploys

Other rules:

- **Do not edit files on the VM.** Fix in git locally → commit → push → on VM `git pull && docker compose --env-file .env.prod up -d --build`. Deploy flow is documented in `deploy/README.md`.
- **Do not exfiltrate `.env.prod`, JWT_SECRET, POSTGRES_PASSWORD, or any JWT** back to chat. Read values inline on the VM only.
- When pasting logs back to the user, **redact anything starting with `eyJ`** (JWT header) and any `POSTGRES_PASSWORD=...` line.

## Quick reference

Prefix every remote command with:
`gcloud compute ssh werewolf-server --zone=us-east1-d --command="cd /opt/werewolf-simple && ..."`

Inside that, `DC` = `sudo docker compose --env-file .env.prod` (both bits are required on this VM).

| Need | Command tail |
|---|---|
| Health check | `curl -sf http://127.0.0.1:8080/api/health` |
| Container status | `DC ps` |
| Backend log (recent) | `DC logs --since=10m backend` |
| Backend log (N lines) | `DC logs --tail=200 backend` |
| Frontend container log | `DC logs --tail=200 frontend` |
| Host nginx error log | `sudo tail -n 200 /var/log/nginx/error.log` |
| DB one-off query | `DC exec -T postgres psql -U werewolf -d werewolf -c '<SQL>'` |
| List tables | `DC exec -T postgres psql -U werewolf -d werewolf -c '\dt'` |
| Active Spring profile | `grep SPRING_PROFILES_ACTIVE .env.prod` |
| Run a debug script | `BACKEND_BASE=http://127.0.0.1:8080/api ./scripts/act.sh STATUS --room ABCD` |
| Disk space | `df -h /` |

## Common failure modes

| Symptom | Likely cause | Fix |
|---|---|---|
| `gcloud compute ssh` → permission denied | Token expired / wrong project | Re-run `gcloud auth login` and `gcloud config set project werewolf-301709` |
| `permission denied while trying to connect to the Docker daemon socket at unix:///var/run/docker.sock` | User not in `docker` group on this VM | Prefix with `sudo` |
| `warning: The "POSTGRES_USER" variable is not set. Defaulting to a blank string.` (and friends) | `docker compose` didn't load `.env.prod` | Add `--env-file .env.prod` |
| `the input device is not a TTY` | Missing `-T` on `docker compose exec` in non-interactive mode | Add `-T` |
| `column "id" does not exist` on `rooms` / `games` / `users` | PK columns are domain-prefixed, not `id` | Use `room_id`, `game_id`, `user_id` |
| `dev-login.sh` → 404 on `/api/auth/dev` | Backend running `prod` profile | Expected — use read-only routes or DB instead |
| SSH command hangs forever | Used `docker compose logs -f` with `--command` | Swap to `--tail=N` or `--since=Xm` |
| Script works locally, fails on VM with same room code | Different `BACKEND_BASE` — local state file `/tmp/werewolf-*.json` holds tokens from the OTHER backend | Prefer port-forward (Step 2) so local state stays consistent |
