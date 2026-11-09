#!/usr/bin/env bash
# Entorno LOCAL y AISLADO de Supabase Storage para las pruebas opt-in de U7b (ver docs/DOCUMENTOS.md).
#
# Levanta SOLO componentes oficiales de Supabase, en una red propia y con nombres propios (prefijo
# banfield-u7b-): Postgres, Storage API y Kong como pasarela /storage/v1 (la misma ruta que usa el
# adaptador contra Supabase). No toca ningún otro contenedor, red ni volumen, ni publica más que un
# puerto en 127.0.0.1. Las claves se generan en cada "up", se guardan en un archivo 0600 FUERA del
# repositorio y nunca se imprimen.
#
# Uso:   backend/scripts/storage-local-u7b.sh up | env | down | estado
#   up      crea la red, los contenedores y el bucket "banfield-storage-test" (privado)
#   env     imprime los "export" que consumen las pruebas:  eval "$(backend/scripts/storage-local-u7b.sh env)"
#   down    elimina SOLO los recursos con la etiqueta proyecto=banfield-patin-u7b y el archivo de claves
#   estado  lista esos recursos
set -euo pipefail

PREFIJO=banfield-u7b
ETIQUETA=proyecto=banfield-patin-u7b
RED=${PREFIJO}-red
BUCKET=banfield-storage-test
PUERTO=${U7B_PUERTO:-15321}
DIR=${XDG_RUNTIME_DIR:-/tmp}/${PREFIJO}-$(id -u)
ARCHIVO=$DIR/entorno
IMG_DB=${U7B_IMG_DB:-public.ecr.aws/supabase/postgres:17.11.0.002}
IMG_STORAGE=${U7B_IMG_STORAGE:-public.ecr.aws/supabase/storage-api:v1.79.28}
IMG_KONG=${U7B_IMG_KONG:-public.ecr.aws/supabase/kong:2.8.1}

jwt() { # jwt <rol> <secreto>
  python3 - "$1" "$2" <<'PY'
import base64, hashlib, hmac, json, sys, time
rol, secreto = sys.argv[1], sys.argv[2]
b = lambda d: base64.urlsafe_b64encode(d).rstrip(b"=")
cab = b(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
ahora = int(time.time())
car = b(json.dumps({"role": rol, "iss": "banfield-u7b-local", "iat": ahora, "exp": ahora + 7 * 86400}).encode())
firma = b(hmac.new(secreto.encode(), cab + b"." + car, hashlib.sha256).digest())
print((cab + b"." + car + b"." + firma).decode())
PY
}

recursos() { docker ps -a --filter "label=$ETIQUETA" --format '{{.Names}}'; }

cmd_down() {
  local c
  for c in $(recursos); do docker rm -f -v "$c" >/dev/null; echo "eliminado: $c"; done
  if docker network inspect "$RED" >/dev/null 2>&1 &&
     [ "$(docker network inspect "$RED" --format '{{index .Labels "proyecto"}}')" = banfield-patin-u7b ]; then
    docker network rm "$RED" >/dev/null && echo "eliminada: red $RED"
  fi
  rm -rf "$DIR" && echo "eliminado: $DIR"
}

cmd_up() {
  if [ -n "$(recursos)" ]; then echo "Ya existen recursos de U7b; use 'down' primero." >&2; exit 1; fi
  if (ss -ltnH 2>/dev/null | awk '{print $4}' | grep -q ":${PUERTO}\$"); then
    echo "El puerto ${PUERTO} está ocupado; defina U7B_PUERTO." >&2; exit 1
  fi
  umask 077; mkdir -p "$DIR"
  local secreto clave_db servicio anon
  secreto=$(openssl rand -hex 24); clave_db=$(openssl rand -hex 16)
  servicio=$(jwt service_role "$secreto"); anon=$(jwt anon "$secreto")
  cat >"$ARCHIVO" <<EOF
export STORAGE_TEST_URL=http://127.0.0.1:${PUERTO}
export STORAGE_TEST_BUCKET=${BUCKET}
export STORAGE_TEST_SERVICE_KEY=${servicio}
export STORAGE_TEST_ANON_KEY=${anon}
export STORAGE_TEST_JWT_SECRET=${secreto}
export STORAGE_TEST_CONTENEDOR=${PREFIJO}-storage
export STORAGE_TEST_PASARELA=${PREFIJO}-kong
EOF
  chmod 600 "$ARCHIVO"

  docker network create --label "$ETIQUETA" "$RED" >/dev/null
  docker run -d --name ${PREFIJO}-db --label "$ETIQUETA" --network "$RED" --network-alias db \
    -e POSTGRES_PASSWORD="$clave_db" -e JWT_SECRET="$secreto" "$IMG_DB" >/dev/null
  for _ in $(seq 1 90); do
    docker exec ${PREFIJO}-db pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1 && break; sleep 1
  done
  # La CLI oficial asigna la contraseña a los roles de servicio al inicializar; aquí se hace igual.
  docker exec ${PREFIJO}-db psql -U supabase_admin -d postgres -v ON_ERROR_STOP=1 -q \
    -c "ALTER USER supabase_storage_admin WITH PASSWORD '${clave_db}'" >/dev/null

  docker run -d --name ${PREFIJO}-storage --label "$ETIQUETA" --network "$RED" --network-alias storage \
    -e ANON_KEY="$anon" -e SERVICE_KEY="$servicio" -e POSTGREST_URL=http://rest:3000 \
    -e PGRST_JWT_SECRET="$secreto" \
    -e DATABASE_URL="postgres://supabase_storage_admin:${clave_db}@db:5432/postgres" \
    -e FILE_SIZE_LIMIT=52428800 -e STORAGE_BACKEND=file -e FILE_STORAGE_BACKEND_PATH=/var/lib/storage \
    -e TENANT_ID=banfield-u7b -e REGION=local -e GLOBAL_S3_BUCKET=local \
    -e ENABLE_IMAGE_TRANSFORMATION=false "$IMG_STORAGE" >/dev/null

  cat >"$DIR/kong.yml" <<'EOF'
_format_version: "2.1"
services:
  - name: storage-v1
    url: http://storage:5000/
    routes:
      - name: storage-v1-all
        strip_path: true
        paths:
          - /storage/v1/
EOF
  chmod 644 "$DIR/kong.yml"  # sin secretos; el usuario de Kong debe poder leerlo
  docker run -d --name ${PREFIJO}-kong --label "$ETIQUETA" --network "$RED" \
    -p 127.0.0.1:${PUERTO}:8000 -e KONG_DATABASE=off -e KONG_DECLARATIVE_CONFIG=/kong.yml \
    -e KONG_DNS_ORDER=LAST,A,CNAME -e KONG_DNS_VALID_TTL=1 -e KONG_NGINX_PROXY_PROXY_BUFFER_SIZE=160k \
    -e KONG_NGINX_PROXY_PROXY_BUFFERS="64 160k" -v "$DIR/kong.yml:/kong.yml:ro" "$IMG_KONG" >/dev/null

  # shellcheck disable=SC1090
  . "$ARCHIVO"
  for _ in $(seq 1 120); do
    codigo=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $STORAGE_TEST_SERVICE_KEY" \
      -H "apikey: $STORAGE_TEST_SERVICE_KEY" "$STORAGE_TEST_URL/storage/v1/bucket" || true)
    [ "$codigo" = 200 ] && break; sleep 1
  done
  [ "$codigo" = 200 ] || { echo "Storage no quedó disponible (HTTP $codigo)." >&2; exit 1; }
  curl -s -o /dev/null -w 'bucket: HTTP %{http_code}\n' -X POST "$STORAGE_TEST_URL/storage/v1/bucket" \
    -H "Authorization: Bearer $STORAGE_TEST_SERVICE_KEY" -H "apikey: $STORAGE_TEST_SERVICE_KEY" \
    -H 'Content-Type: application/json' -d "{\"id\":\"$BUCKET\",\"name\":\"$BUCKET\",\"public\":false}"
  echo "Listo: Storage local en 127.0.0.1:${PUERTO}, bucket ${BUCKET} (privado). Claves en $ARCHIVO (0600)."
}

case "${1:-}" in
  up) cmd_up ;;
  env) cat "$ARCHIVO" ;;
  down) cmd_down ;;
  estado) docker ps -a --filter "label=$ETIQUETA" --format '{{.Names}} {{.Status}}' ;;
  *) sed -n '2,17p' "$0"; exit 2 ;;
esac
