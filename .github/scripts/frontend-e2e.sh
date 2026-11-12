#!/usr/bin/env bash
# E2E reales del frontend: PostgreSQL descartable (Docker) + backend Spring + Vite + Playwright.
# Es el mismo script en GitHub Actions y en local. Requisitos: Docker, Java 21, Node con las dependencias de
# frontend/ instaladas (npm ci) y el Chromium de Playwright (npx playwright install chromium); `setsid` (Linux).
#
# Nada sensible se guarda: las credenciales se generan en cada ejecución, viven solo en el entorno de este proceso
# y la base (un contenedor propio, con nombre único) se elimina al terminar. No toca otros contenedores.
#
# Variables opcionales: E2E_PG_PORT (puerto local de la base, 55432 por defecto).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PG_NAME="bp-e2e-postgres-${GITHUB_RUN_ID:-local}-$$"
PG_PORT="${E2E_PG_PORT:-55432}"
PG_IMAGE="postgres:17-alpine"
PG_USER="bp_e2e"
PG_DB="bp_e2e"
BACKEND_PORT=8080 # el proxy de Vite apunta aquí por defecto
WORK="$(mktemp -d)"
BACKEND_PID=""
STATE_FILE="$ROOT/frontend/e2e/.state.json"

cleanup() {
  local status=$?
  if [ "$status" -ne 0 ] && [ -f "$WORK/backend.log" ]; then
    echo "--- últimas líneas del log del backend ---"
    tail -n 80 "$WORK/backend.log" || true
  fi
  if [ -n "$BACKEND_PID" ]; then
    kill -- "-$BACKEND_PID" 2>/dev/null || true # grupo propio: incluye la JVM que lanza Maven
  fi
  docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
  rm -f "$STATE_FILE" # secreto TOTP de la base descartable
  rm -rf "$WORK"
  exit "$status"
}
trap cleanup EXIT

# Estado de una corrida anterior: la base es nueva, así que el enrolamiento TOTP se repite.
rm -f "$STATE_FILE"

if [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${BACKEND_PORT}/api/auth/csrf" || true)" != "000" ]; then
  echo "El puerto ${BACKEND_PORT} ya está en uso: detené ese backend antes de correr los E2E." >&2
  exit 1
fi

# Credenciales efímeras.
DB_PASSWORD="$(openssl rand -hex 16)"
JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n')"
MFA_CLAVE_CIFRADO="$(openssl rand -base64 32 | tr -d '\n')"
ADMIN_EMAIL="ci-admin@example.test"
ADMIN_PASSWORD="Ci-$(openssl rand -hex 10)-Aa1"
if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
  for value in "$DB_PASSWORD" "$JWT_SECRET" "$MFA_CLAVE_CIFRADO" "$ADMIN_PASSWORD"; do
    echo "::add-mask::${value}"
  done
fi

echo "Iniciando PostgreSQL descartable (${PG_IMAGE}) en 127.0.0.1:${PG_PORT}..."
docker run -d --name "$PG_NAME" \
  -e POSTGRES_USER="$PG_USER" -e POSTGRES_DB="$PG_DB" -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  -p "127.0.0.1:${PG_PORT}:5432" "$PG_IMAGE" >/dev/null
ready=0
for _ in $(seq 1 60); do
  # -h 127.0.0.1: por TCP, para no confundir el servidor temporal de inicialización con el definitivo.
  if docker exec "$PG_NAME" pg_isready -h 127.0.0.1 -U "$PG_USER" -d "$PG_DB" -q; then ready=1; break; fi
  sleep 1
done
[ "$ready" = 1 ] || { echo "PostgreSQL no quedó listo a tiempo." >&2; exit 1; }

echo "Iniciando el backend..."
setsid env \
  SPRING_PROFILES_ACTIVE=dev \
  DB_URL="jdbc:postgresql://127.0.0.1:${PG_PORT}/${PG_DB}" DB_USERNAME="$PG_USER" DB_PASSWORD="$DB_PASSWORD" \
  JWT_SECRET="$JWT_SECRET" MFA_CLAVE_CIFRADO="$MFA_CLAVE_CIFRADO" \
  AUTH_COOKIE_SECURE=false FRONTEND_URL_BASE="http://127.0.0.1:5173" \
  BOOTSTRAP_ADMIN_HABILITADO=true BOOTSTRAP_ADMIN_EMAIL="$ADMIN_EMAIL" BOOTSTRAP_ADMIN_NOMBRE=Admin \
  BOOTSTRAP_ADMIN_APELLIDO=CI BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  DOCUMENTOS_CARGA_HABILITADA=false DOCUMENTOS_STORAGE_HABILITADO=false \
  "$ROOT/backend/mvnw" -B -q -f "$ROOT/backend/pom.xml" spring-boot:run >"$WORK/backend.log" 2>&1 &
BACKEND_PID=$!

up=0
for _ in $(seq 1 120); do
  kill -0 "$BACKEND_PID" 2>/dev/null || { echo "El backend terminó antes de arrancar." >&2; exit 1; }
  if curl -fsS -o /dev/null "http://127.0.0.1:${BACKEND_PORT}/api/auth/csrf" 2>/dev/null; then up=1; break; fi
  sleep 2
done
[ "$up" = 1 ] || { echo "El backend no respondió a tiempo." >&2; exit 1; }
echo "Backend listo. Ejecutando Playwright..."

cd "$ROOT/frontend"
E2E_ADMIN_EMAIL="$ADMIN_EMAIL" E2E_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  E2E_DB_CONTAINER="$PG_NAME" E2E_DB_NAME="$PG_DB" \
  npm run test:e2e
