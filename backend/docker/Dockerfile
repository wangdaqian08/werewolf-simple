FROM postgres:16-alpine

# Credentials are passed in at runtime via docker-compose (from .env).
# Do not set defaults here.

# Copy init script to create additional roles or settings if needed
COPY init.sql /docker-entrypoint-initdb.d/

EXPOSE 5432
