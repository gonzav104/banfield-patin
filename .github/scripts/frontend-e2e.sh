#!/usr/bin/env bash
# E2E reales del frontend: PostgreSQL descartable (Docker) + backend Spring + Vite + Playwright.
# Es el mismo script en GitHub Actions y en local. Requisitos: Docker, Java 21, Node con las dependencias de
# frontend/ instaladas (npm ci) y el Chromium de Playwright (npx playwright install chromium); `setsid` (Linux).
#
# Nada sensible se guarda: las credenciales se generan en cada ejecución, viven solo en el entorno de este proceso
# y la base (un contenedor propio, con nombre único) se elimina al terminar. No toca otros contenedores.
#
# Carga documental (U15): el backend arranca con la carga HABILITADA contra dos DOBLES de prueba que este script levanta en
# 127.0.0.1 (puertos libres) y detiene al terminar: frontend/e2e/support/fakes/storage-fake.ts (subconjunto de la API de
# Supabase Storage) y frontend/e2e/support/fakes/clamd-fake.ts (INSTREAM de clamd; solo reconoce la cadena de prueba EICAR).
# Viven únicamente en la infraestructura de pruebas: el código y la configuración del backend no cambian y sus valores por
# defecto siguen con la carga deshabilitada. No reemplazan las pruebas opt-in contra Supabase y ClamAV reales.
#
# Variables opcionales:
#   E2E_PG_PORT (puerto local de la base, 55432 por defecto).
#   E2E_SHARD   (numero de shard, segun frontend/e2e/shards.json). Sin ella corre la suite COMPLETA, como siempre. Con ella corre solo
#               los archivos de ese shard, en una base, un backend y unas credenciales propios: los shards de CI son runners distintos
#               y cada uno tiene su administrador. Dos shards no pueden correr a la vez en la misma maquina (mismos puertos 8080/5173).
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
STORAGE_PID=""
CLAMD_PID=""
STORAGE_BUCKET="bp-e2e-documentos"
STORAGE_OBJECTS="$WORK/storage-objects" # inspección de prueba: los objetos que recibió el doble de Storage
STATE_FILE="$ROOT/frontend/e2e/.state.json"

cleanup() {
  local status=$?
  if [ "$status" -ne 0 ] && [ -f "$WORK/backend.log" ]; then
    echo "--- últimas líneas del log del backend ---"
    tail -n 80 "$WORK/backend.log" || true
    for fake in storage-fake clamd-fake; do # solo forma de ruta y veredicto: ni claves, ni credencial, ni contenido
      if [ -f "$WORK/${fake}.log" ]; then echo "--- últimas líneas de ${fake} ---"; tail -n 20 "$WORK/${fake}.log" || true; fi
    done
  fi
  if [ -n "$BACKEND_PID" ]; then
    kill -- "-$BACKEND_PID" 2>/dev/null || true # grupo propio: incluye la JVM que lanza Maven
  fi
  for pid in "$STORAGE_PID" "$CLAMD_PID"; do
    if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; fi
  done
  docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
  rm -f "$STATE_FILE" # secreto TOTP de la base descartable
  rm -rf "$WORK"
  exit "$status"
}
trap cleanup EXIT

# Estado de una corrida anterior: la base es nueva, así que el enrolamiento TOTP se repite.
rm -f "$STATE_FILE"

# Reparto por shards: se valida ANTES de levantar nada. Ni falta, ni sobra, ni se repite ningún escenario de los 102 y el spec que
# enrola al administrador va primero en su shard (si algo no cierra, esto falla y no se ejecuta ningún shard a medias).
SHARD="${E2E_SHARD:-}"
E2E_ARGS=()
NEEDS_ENROLMENT="no"
if [ -n "$SHARD" ]; then
  (cd "$ROOT/frontend" && node e2e/support/shards.ts check)
  # Asignaciones simples (no `done < <(...)`): si el shard no existe, el comando falla y `set -e` corta acá. Un shard inválido NUNCA
  # debe terminar ejecutando la suite completa.
  SHARD_PATTERNS="$(cd "$ROOT/frontend" && node e2e/support/shards.ts patterns "$SHARD")"
  NEEDS_ENROLMENT="$(cd "$ROOT/frontend" && node e2e/support/shards.ts needs-enrolment "$SHARD")"
  while IFS= read -r pattern; do
    if [ -n "$pattern" ]; then E2E_ARGS+=("$pattern"); fi
  done <<<"$SHARD_PATTERNS"
  if [ "${#E2E_ARGS[@]}" -eq 0 ]; then echo "El shard ${SHARD} no tiene archivos de E2E." >&2; exit 1; fi
  echo "Shard ${SHARD}: ${#E2E_ARGS[@]} archivo(s) de E2E."
fi

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
# Credencial SINTÉTICA del doble de Storage, con forma de JWT de rol service_role (U7c: solo esa forma permite confirmar una
# ausencia). No es una clave de Supabase ni está firmada: el doble solo la compara con la que recibe.
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
STORAGE_CREDENTIAL="$(printf '{"alg":"none","typ":"JWT"}' | b64url).$(printf '{"role":"service_role","iss":"bp-e2e-sintetico"}' | b64url).$(openssl rand -hex 16)"
if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
  for value in "$DB_PASSWORD" "$JWT_SECRET" "$MFA_CLAVE_CIFRADO" "$ADMIN_PASSWORD" "$STORAGE_CREDENTIAL"; do
    echo "::add-mask::${value}"
  done
fi

echo "Iniciando PostgreSQL descartable (${PG_IMAGE}) en 127.0.0.1:${PG_PORT}..."
# La etiqueta es la que exige frontend/e2e/support/db.ts antes de ejecutar SQL destructivo: solo este contenedor la lleva.
docker run -d --name "$PG_NAME" --label "com.banfieldpatin.e2e.disposable=true" \
  -e POSTGRES_USER="$PG_USER" -e POSTGRES_DB="$PG_DB" -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  -p "127.0.0.1:${PG_PORT}:5432" "$PG_IMAGE" >/dev/null
ready=0
for _ in $(seq 1 60); do
  # -h 127.0.0.1: por TCP, para no confundir el servidor temporal de inicialización con el definitivo.
  if docker exec "$PG_NAME" pg_isready -h 127.0.0.1 -U "$PG_USER" -d "$PG_DB" -q; then ready=1; break; fi
  sleep 1
done
[ "$ready" = 1 ] || { echo "PostgreSQL no quedó listo a tiempo." >&2; exit 1; }

echo "Iniciando los dobles de Storage y clamd (solo 127.0.0.1)..."
mkdir -m 700 "$STORAGE_OBJECTS" "$WORK/cargas"
E2E_STORAGE_CREDENTIAL="$STORAGE_CREDENTIAL" node "$ROOT/frontend/e2e/support/fakes/storage-fake.ts" \
  --port-file "$WORK/storage.port" --objects-dir "$STORAGE_OBJECTS" --bucket "$STORAGE_BUCKET" >"$WORK/storage-fake.log" 2>&1 &
STORAGE_PID=$!
node "$ROOT/frontend/e2e/support/fakes/clamd-fake.ts" --port-file "$WORK/clamd.port" >"$WORK/clamd-fake.log" 2>&1 &
CLAMD_PID=$!
fakes=0
for _ in $(seq 1 100); do
  kill -0 "$STORAGE_PID" 2>/dev/null && kill -0 "$CLAMD_PID" 2>/dev/null || { echo "Un doble de prueba terminó antes de arrancar." >&2; exit 1; }
  if [ -s "$WORK/storage.port" ] && [ -s "$WORK/clamd.port" ]; then fakes=1; break; fi
  sleep 0.1
done
[ "$fakes" = 1 ] || { echo "Los dobles de prueba no quedaron listos a tiempo." >&2; exit 1; }
STORAGE_PORT="$(cat "$WORK/storage.port")"
CLAMD_PORT="$(cat "$WORK/clamd.port")"

echo "Iniciando el backend..."
setsid env \
  SPRING_PROFILES_ACTIVE=dev \
  DB_URL="jdbc:postgresql://127.0.0.1:${PG_PORT}/${PG_DB}" DB_USERNAME="$PG_USER" DB_PASSWORD="$DB_PASSWORD" \
  JWT_SECRET="$JWT_SECRET" MFA_CLAVE_CIFRADO="$MFA_CLAVE_CIFRADO" \
  AUTH_COOKIE_SECURE=false FRONTEND_URL_BASE="http://127.0.0.1:5173" \
  BOOTSTRAP_ADMIN_HABILITADO=true BOOTSTRAP_ADMIN_EMAIL="$ADMIN_EMAIL" BOOTSTRAP_ADMIN_NOMBRE=Admin \
  BOOTSTRAP_ADMIN_APELLIDO=CI BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  DOCUMENTOS_CARGA_HABILITADA=true DOCUMENTOS_STORAGE_HABILITADO=true \
  SUPABASE_URL="http://127.0.0.1:${STORAGE_PORT}" SUPABASE_BUCKET_DOCUMENTOS="$STORAGE_BUCKET" \
  SUPABASE_SERVICE_ROLE_KEY="$STORAGE_CREDENTIAL" \
  BANFIELD_DOCUMENTOS_CARGA_SCANNERHOST=127.0.0.1 BANFIELD_DOCUMENTOS_CARGA_SCANNERPUERTO="$CLAMD_PORT" \
  BANFIELD_DOCUMENTOS_CARGA_TEMPORALES="$WORK/cargas" \
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
# Un shard que no contiene auth.real.spec.ts (el spec que enrola al administrador por la interfaz) lo enrola antes por la API real:
# su base es propia y todos los demás specs dependen de un administrador enrolado. No es un escenario ni cambia ninguno.
if [ "$NEEDS_ENROLMENT" = "yes" ]; then
  E2E_ADMIN_EMAIL="$ADMIN_EMAIL" E2E_ADMIN_PASSWORD="$ADMIN_PASSWORD" E2E_BACKEND_URL="http://127.0.0.1:${BACKEND_PORT}" \
    node e2e/support/enrol-admin.ts
fi
E2E_ADMIN_EMAIL="$ADMIN_EMAIL" E2E_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  E2E_DB_CONTAINER="$PG_NAME" E2E_DB_NAME="$PG_DB" \
  E2E_STORAGE_OBJECTS_DIR="$STORAGE_OBJECTS" E2E_STORAGE_BUCKET="$STORAGE_BUCKET" \
  npm run test:e2e -- ${E2E_ARGS[@]+"${E2E_ARGS[@]}"}
