# Werewolf — Production Deployment

Single-command container deployment. Host Nginx terminates TLS and reverse-proxies
to a docker-compose stack (backend, frontend, Postgres) bound to `127.0.0.1`.

## One-time host setup (GCP free-tier Ubuntu 22.04)

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

## Deploy (single command)

```bash
cd /opt/werewolf-simple
cp .env.prod.example .env.prod
# edit .env.prod — fill in JWT_SECRET, POSTGRES_PASSWORD, GOOGLE_*, FRONTEND_ORIGIN
docker compose up -d --build
```

Everything needed (Postgres, backend jar build, frontend Vite build, Nginx
container) is produced by this single command. No local Java / Node install
required on the VM.

### Updates

```bash
cd /opt/werewolf-simple
git pull
docker compose up -d --build
```

## Verify

```bash
# Should return {"status":"UP"}
curl -sf http://127.0.0.1:8080/api/health

# Via host Nginx (HTTPS after certbot)
curl -sf https://werewolf.example.com/api/health
```

## Required env vars

See `/Users/dq/workspace/werewolf-simple/.env.prod.example` at the repo root
for the full annotated list. The backend refuses to start if any of these are
unset:

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

## Legacy: systemd + plain jar

The `werewolf-backend.service` unit and scp-based jar deploy flow are retained
for users who prefer a non-container install. For container deploys (recommended)
use the `docker compose` flow above.

```bash
# Locally build the fat jar
cd backend && ./gradlew bootJar

# Copy to VM
scp build/libs/werewolf-*-SNAPSHOT.jar vm:/tmp/werewolf-backend.jar
ssh vm sudo install -o werewolf -g werewolf -m 640 \
    /tmp/werewolf-backend.jar /opt/werewolf/werewolf-backend.jar

sudo systemctl restart werewolf-backend
sudo journalctl -u werewolf-backend -f
```

See `werewolf-backend.service` for the systemd unit. You must still provision
Postgres separately in that flow (the docker-compose stack handles it for you).
