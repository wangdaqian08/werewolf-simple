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

1. On the repo: tag main with `vX.Y.Z` and push. GitHub Actions builds + pushes
   `ghcr.io/.../werewolf-simple-{backend,frontend}:vX.Y.Z` + `:latest`.
2. On the VM:
   ```bash
   cd /opt/werewolf-simple
   sed -i 's/^WEREWOLF_VERSION=.*$/WEREWOLF_VERSION=vX.Y.Z/' .env.prod
   docker compose --env-file .env.prod pull
   docker compose --env-file .env.prod up -d
   ```
   `up -d` detects the image reference changed and recreates the container; no
   `--force-recreate` needed.

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
