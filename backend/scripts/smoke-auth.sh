#!/usr/bin/env bash
# Smoke test manual de autenticacion contra una instancia ya levantada del backend.
# Uso:  BASE_URL=http://localhost:8080 ./scripts/smoke-auth.sh
# Pide por teclado el email, la password y el codigo TOTP (nada se guarda ni se imprime).
# ATENCION: crea una familia "SMOKE (borrar)" y una invitacion que termina revocada;
# no existe endpoint para borrar la familia, queda como dato de prueba.
set -uo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT
FALLOS=0

ok()   { echo "  [OK]    $1"; }
fail() { echo "  [FALLA] $1"; FALLOS=$((FALLOS + 1)); }

csrf() {
  curl -s --max-time 15 -b "$JAR" -c "$JAR" "$BASE/api/auth/csrf" -o /dev/null
  TOKEN="$(awk '$6=="XSRF-TOKEN"{print $7}' "$JAR" | tail -n1)"
}

# post <ruta> [json]  -> deja CUERPO y ESTADO
post() {
  local ruta="$1" cuerpo="${2:-}"
  local args=(-s --max-time 20 -b "$JAR" -c "$JAR" -X POST -H "X-XSRF-TOKEN: $TOKEN" -w '\n%{http_code}')
  [ -n "$cuerpo" ] && args+=(-H 'Content-Type: application/json' --data "$cuerpo")
  local salida; salida="$(curl "${args[@]}" "$BASE$ruta")"
  ESTADO="${salida##*$'\n'}"; CUERPO="${salida%$'\n'*}"
}

get() {
  local salida; salida="$(curl -s --max-time 20 -b "$JAR" -c "$JAR" -w '\n%{http_code}' "$BASE$1")"
  ESTADO="${salida##*$'\n'}"; CUERPO="${salida%$'\n'*}"
}

campo() { python3 -c 'import sys,json
try:
    d=json.load(sys.stdin)
    for k in sys.argv[1].split("."):
        d=d[k]
    print(str(d).lower() if isinstance(d,bool) else d)
except Exception:
    print("")' "$1"; }

esperar() { # esperar <estado-esperado> <descripcion>
  if [ "$ESTADO" = "$1" ]; then ok "$2 (HTTP $ESTADO)"; else fail "$2: esperado $1, recibido $ESTADO"; fi
}

read -r -p "Email ADMIN: " EMAIL
read -r -s -p "Password ADMIN: " PASS; echo
echo "== 1. Login ADMIN"
csrf
CUERPO_LOGIN="$(python3 -c 'import json,sys;print(json.dumps({"email":sys.argv[1],"password":sys.argv[2]}))' "$EMAIL" "$PASS")"
unset PASS
post /api/auth/admin/login "$CUERPO_LOGIN"; unset CUERPO_LOGIN
esperar 200 "login ADMIN (sesion MFA pendiente)"
[ "$(echo "$CUERPO" | campo mfaPendiente)" = "true" ] && ok "mfaPendiente=true" || fail "se esperaba mfaPendiente=true"
ENROLADO="$(echo "$CUERPO" | campo mfaEnrolado)"

echo "== 2. Sesion pendiente no accede a /api/admin"
csrf; get /api/admin/invitaciones; esperar 403 "GET /api/admin/invitaciones con MFA pendiente"

echo "== 3. MFA"
csrf
if [ "$ENROLADO" != "true" ]; then
  post /api/auth/admin/mfa/enrolar
  esperar 200 "enrolar TOTP"
  echo "  Agrega este secreto a tu app autenticadora (Base32): $(echo "$CUERPO" | campo secretoBase32)"
  echo "  o escanea/pega el URI: $(echo "$CUERPO" | campo otpauthUri)"
  read -r -p "Codigo de 6 digitos: " CODIGO
  csrf; post /api/auth/admin/mfa/confirmar "{\"codigo\":\"$CODIGO\"}"
  esperar 200 "confirmar TOTP"
else
  read -r -p "Codigo de 6 digitos: " CODIGO
  post /api/auth/admin/mfa/verificar "{\"codigo\":\"$CODIGO\"}"
  esperar 200 "verificar TOTP"
fi
unset CODIGO

echo "== 4. /api/auth/me"
csrf; get /api/auth/me
esperar 200 "GET /api/auth/me"
[ "$(echo "$CUERPO" | campo mfaPendiente)" = "false" ] && ok "mfaPendiente=false (sesion completa)" || fail "se esperaba mfaPendiente=false"
[ "$(echo "$CUERPO" | campo rol)" = "ADMIN" ] && ok "rol=ADMIN" || fail "se esperaba rol=ADMIN"

echo "== 5. Crear invitacion"
post /api/admin/invitaciones '{"nuevaFamilia":{"nombreReferencia":"SMOKE (borrar)"},"diasVigencia":1}'
esperar 201 "crear invitacion"
INV_ID="$(echo "$CUERPO" | campo id)"
INV_TOKEN="$(echo "$CUERPO" | campo token)"
[ -n "$INV_ID" ] && [ -n "$INV_TOKEN" ] && ok "respuesta con id y token (no se imprimen)" || fail "respuesta sin id/token"
[ "$(echo "$CUERPO" | campo estado)" = "PENDIENTE" ] && ok "estado=PENDIENTE" || fail "se esperaba estado=PENDIENTE"

echo "== 6. Validar invitacion (endpoint publico)"
post /api/auth/invitaciones/validar "{\"token\":\"$INV_TOKEN\"}"
esperar 200 "validar invitacion vigente"

echo "== 7. Revocar invitacion"
post "/api/admin/invitaciones/$INV_ID/revocar"
esperar 200 "revocar invitacion"
post /api/auth/invitaciones/validar "{\"token\":\"$INV_TOKEN\"}"
esperar 400 "validar invitacion revocada (uniforme INVITACION_NO_DISPONIBLE)"
unset INV_TOKEN

echo "== 8. Logout"
post /api/auth/logout
esperar 204 "logout"

echo
if [ "$FALLOS" -eq 0 ]; then echo "SMOKE OK"; else echo "SMOKE CON $FALLOS FALLA(S)"; exit 1; fi
