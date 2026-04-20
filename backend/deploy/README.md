# Werewolf Backend — Production Deployment

Minimal runbook for deploying the Spring Boot backend to a GCP free-tier VM
(Ubuntu 22.04, 1 GB RAM, Docker-based Postgres on `127.0.0.1:5432`).

## One-time host setup

```bash
# 1. Install Java 17 + Docker (Postgres will run as a container)
sudo apt update
sudo apt install -y openjdk-17-jre-headless docker.io docker-compose-plugin

# 2. Create service user and directories
sudo useradd --system --home /opt/werewolf --shell /usr/sbin/nologin werewolf
sudo mkdir -p /opt/werewolf /etc/werewolf /var/log/werewolf
sudo chown werewolf:werewolf /opt/werewolf /var/log/werewolf
sudo chmod 750 /etc/werewolf

# 3. Install the env file (chmod 640 — contains secrets)
sudo cp backend/.env.prod.example /etc/werewolf/env
sudo chown root:werewolf /etc/werewolf/env
sudo chmod 640 /etc/werewolf/env
sudo -e /etc/werewolf/env   # edit and fill in real values

# 4. Install the systemd unit
sudo cp backend/deploy/werewolf-backend.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable werewolf-backend
```

## Build and deploy

```bash
# Locally: build the fat jar
cd backend
./gradlew bootJar

# Copy to VM
scp build/libs/werewolf-*-SNAPSHOT.jar vm:/tmp/werewolf-backend.jar
ssh vm sudo install -o werewolf -g werewolf -m 640 \
    /tmp/werewolf-backend.jar /opt/werewolf/werewolf-backend.jar
```

## Start / stop / logs

```bash
sudo systemctl start werewolf-backend
sudo systemctl stop werewolf-backend
sudo systemctl restart werewolf-backend
sudo systemctl status werewolf-backend

# Live logs
sudo journalctl -u werewolf-backend -f

# Rotated file logs (written by logback)
tail -f /var/log/werewolf/backend.log
```

## Required env vars

See `backend/.env.prod.example` for the full list with comments.

The backend will refuse to start if any of these are unset:
`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `JWT_SECRET`,
`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`,
`FRONTEND_ORIGIN`.

## Nginx reverse proxy

The backend expects `X-Forwarded-Proto` and `X-Forwarded-For` headers
(`server.forward-headers-strategy: native`). A minimal Nginx snippet:

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

location /ws/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 3600s;
}
```

Mobile Safari blocks `ws://`, so SSL (Let's Encrypt) is required.
