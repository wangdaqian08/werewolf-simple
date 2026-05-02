# Werewolf — Production Deployment

Single-command container deployment. Host Nginx terminates TLS and reverse-proxies
to a docker-compose stack (backend, frontend, Postgres) bound to `127.0.0.1`.

Docker is the only supported deployment path.

## One-time host setup (Ubuntu 22.04)

```bash
# 1. Docker + Nginx
sudo apt update
sudo apt install -y docker.io docker-compose-plugin nginx
sudo systemctl enable --now docker nginx

# 2. Allow yourself to run docker without sudo (optional)
sudo usermod -aG docker "$USER"
# log out / back in for the group change to take effect

# 3. Clone the repo (any location; these docs assume /opt/werewolf-simple)
sudo git clone https://github.com/<your>/werewolf-simple.git /opt/werewolf-simple
sudo chown -R "$USER":"$USER" /opt/werewolf-simple
cd /opt/werewolf-simple

# 4. Install the host Nginx site (edit server_name to your domain first)
sudo cp deploy/nginx-site.conf /etc/nginx/sites-available/werewolf
sudo ln -sf /etc/nginx/sites-available/werewolf /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx

# 5. TLS via Let's Encrypt (required — mobile Safari blocks ws://)
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d werewolf.example.com
```

## VM access

```bash
source scripts/.env.vm
gcloud compute ssh "$VM_NAME" --zone="$VM_ZONE" --project="$VM_PROJECT"
```

Requires `scripts/.env.vm` populated locally (copy from `.env.vm.example`).
For one-shot commands use `./scripts/vm-ssh.sh '<cmd>'` — same auth, but
reuses the SSH ControlMaster (~0.5s vs ~3s per call).

## Database access

Postgres binds to `127.0.0.1:5432` on the VM (loopback only — see
`docker-compose.yml`), so external `psql` requires either an SSH tunnel or
`docker exec`.

### Option A — SSH tunnel + local psql (port 15432)

```bash
# Terminal 1 — leave running
source scripts/.env.vm
gcloud compute ssh "$VM_NAME" --zone="$VM_ZONE" --project="$VM_PROJECT" \
  -- -L 15432:localhost:5432 -N

# Terminal 2 — connect to the forwarded port
psql -h localhost -p 15432 -U werewolf -d werewolf
# password is in .env.prod on the VM:
#   ./scripts/vm-ssh.sh 'sudo grep POSTGRES_PASSWORD /opt/werewolf-simple/.env.prod'
```

### Option B — psql inside the postgres container (no tunnel)

```bash
source scripts/.env.vm
gcloud compute ssh "$VM_NAME" --zone="$VM_ZONE" --project="$VM_PROJECT" \
  --command='sudo docker exec -it $(sudo docker ps -qf name=postgres) psql -U werewolf -d werewolf'
```

## Deploy

Images are pre-built by GitHub Actions and published to `ghcr.io` on every
release tag. The prod VM only pulls and starts — no Gradle/Vite build on the
VM, so deploys are ~1 min instead of ~30 min.

### First-time install

```bash
cd /opt/werewolf-simple
cp .env.prod.example .env.prod
# edit .env.prod — fill in JWT_SECRET, POSTGRES_PASSWORD, GOOGLE_*, FRONTEND_ORIGIN
# set WEREWOLF_VERSION=v0.2.0 (or whichever release tag you want to pin)
docker compose --env-file .env.prod pull
docker compose --env-file .env.prod up -d
```

### Release a new version

Substitute `vX.Y.Z` for the version you're shipping (e.g. `v0.3.1`).

#### A. Local — tag and push (triggers the image build)

```bash
git checkout main && git pull origin main
git tag -a vX.Y.Z -m "<one-line release summary>"
git push origin vX.Y.Z
```

The `publish-images.yml` workflow builds + publishes
`ghcr.io/.../werewolf-simple-{backend,frontend}:vX.Y.Z` + `:latest`. Wait
~5–10 min for it to go green:

```bash
gh run watch                            # latest workflow run
```

Don't proceed until both images are published.

#### B. On the VM — advance the tree, pin, pull, recreate

```bash
cd /opt/werewolf-simple

# 1. Move the working tree to the new tag (so docker-compose.yml matches)
git fetch origin --tags
git checkout vX.Y.Z

# 2. Pin the version in .env.prod
#    `sed -i` REPLACES the line; if WEREWOLF_VERSION isn't present yet,
#    append it instead: echo 'WEREWOLF_VERSION=vX.Y.Z' | sudo tee -a .env.prod
sudo sed -i 's/^WEREWOLF_VERSION=.*$/WEREWOLF_VERSION=vX.Y.Z/' .env.prod
grep '^WEREWOLF_VERSION=' .env.prod      # confirm it's set

# 3. Pull the published images and recreate containers.
#    --force-recreate replaces even when compose thinks nothing changed.
sudo docker compose --env-file .env.prod pull
sudo docker compose --env-file .env.prod up -d --force-recreate backend frontend
```

> `sudo` is required throughout: the VM login user isn't in the `docker`
> group, and `.env.prod` is root-owned.

#### C. Verify

```bash
# Image refs of running containers — both should end in :vX.Y.Z
sudo docker compose --env-file .env.prod ps --format '{{.Service}}: {{.Image}}'

# Digest match: running container vs ghcr.io tag
sudo docker inspect --format '{{.Image}}' \
  $(sudo docker compose --env-file .env.prod ps -q backend)
sudo docker inspect --format '{{.Id}}' \
  ghcr.io/wangdaqian08/werewolf-simple-backend:vX.Y.Z
# ↑ both should print the same sha256:...

# Health + recent logs
curl -sf http://127.0.0.1:8080/api/health        # {"status":"UP"}
sudo docker compose --env-file .env.prod logs --since=2m backend | \
  grep -iE 'Started Werewolf|active profile' | head
```

#### D. Rollback (if anything looks wrong)

```bash
git checkout vX.Y.Z-PREVIOUS                                  # e.g. v0.3.0
sudo sed -i 's/^WEREWOLF_VERSION=.*$/WEREWOLF_VERSION=vX.Y.Z-PREVIOUS/' .env.prod
sudo docker compose --env-file .env.prod pull
sudo docker compose --env-file .env.prod up -d --force-recreate backend frontend
```

### Emergency rebuild on the VM (fallback)

If ghcr.io is unreachable or you need to patch without a release:

```bash
cd /opt/werewolf-simple
git pull
docker compose --env-file .env.prod up -d --build
```

`build:` is still in `docker-compose.yml` as a fallback — this takes ~30 min
on a 2-vCPU VM.

## Verify

```bash
# Should return {"status":"UP"}
curl -sf http://127.0.0.1:8080/api/health

# Via host Nginx (HTTPS after certbot)
curl -sf https://werewolf.example.com/api/health
```

## Required env vars

See `.env.prod.example` at the repo root for the full annotated list. The backend
refuses to start if any of these are unset:

`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `JWT_SECRET`,
`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`,
`FRONTEND_ORIGIN`.

## Lifecycle

```bash
docker compose ps                       # status
docker compose logs -f backend          # live logs
docker compose logs -f frontend
docker compose restart backend          # restart one service
docker compose down                     # stop (keeps volumes / data)
docker compose down -v                  # stop AND wipe postgres data
```
