#!/usr/bin/env bash
# Smoke test manual de familias, tutores, deportistas, vinculos y portal de FAMILIA contra una instancia ya levantada.
# Uso:  BASE_URL=http://localhost:8080 ./scripts/smoke-familias.sh
#
# Pide por teclado el email, la password y UN codigo TOTP del ADMIN (nada se guarda ni se imprime). Usa la API real de BASE_URL:
# si BASE_URL apunta a una app conectada a Supabase, ESCRIBE DATOS REALES en esa base (ver "Que crea" abajo).
#
# Que crea (todo identificable por el prefijo SMOKE_<run id>): 2 familias, 1 tutor, 3 deportistas, vinculos, 1 invitacion usada y 1 usuario
# FAMILIA (smoke_<run id>@example.test). Los DNI son numericos de 9 digitos que empiezan con 9 (no admiten prefijo).
# No existe endpoint para borrar: los datos se limpian despues con backend/scripts/limpiar-smoke-familias.sql (el comando exacto, con el run id,
# se imprime al final, tambien si el smoke falla).
#
# Variables opcionales:
#   BASE_URL                    URL base (por defecto http://localhost:8080).
#   SMOKE_SEGUIR_TRAS_FALLA=1   no corta en la primera falla (por defecto si corta: "SMOKE FALLO EN EL PASO N" y exit 1).
#
# Lo que NO se imprime ni se guarda: DNI/CUIL, passwords, codigo TOTP, secreto MFA (salvo al enrolar, ver paso 1), cookies, token CSRF,
# token de invitacion. Solo se muestran estados HTTP, codigos de error, banderas, conteos, el run id y los 6 primeros caracteres de algun id (menos que un DNI, para que no se confunda con uno).
# Las cookies viven en archivos mktemp (permiso 600) que se borran al terminar.
set -uo pipefail
umask 077

BASE="${BASE_URL:-http://localhost:8080}"
SEGUIR="${SMOKE_SEGUIR_TRAS_FALLA:-0}"
JAR_ADMIN="$(mktemp)"; JAR_FAM="$(mktemp)"; JAR_ANON="$(mktemp)"; JAR_COPIA="$(mktemp)"
JAR="$JAR_ANON"
FALLOS=0
PASO=0
PASO_DESC="(antes del primer paso)"
RUN=""
AYUDA_MOSTRADA=0
CREO=0   # pasa a 1 justo antes de la primera escritura de datos SMOKE_

ayuda_limpieza() {
  AYUDA_MOSTRADA=1
  echo
  echo "Run id: $RUN"
  echo "Los datos creados llevan el prefijo $RUN (el DNI no admite prefijo). Para limpiarlos (primero vista previa, no escribe nada):"
  echo "  psql \"\$URL_POSTGRES\" -X -v run_id=$RUN -f backend/scripts/limpiar-smoke-familias.sql"
  echo "y para aplicar la limpieza suave (transaccion unica):"
  echo "  psql \"\$URL_POSTGRES\" -X -v run_id=$RUN -v aplicar=si -f backend/scripts/limpiar-smoke-familias.sql"
  echo "(\$URL_POSTGRES es la URL de conexion de psql, no la JDBC. El usuario FAMILIA registrado y las filas de auditoria quedan como evidencia.)"
}
terminar() {
  rm -f "$JAR_ADMIN" "$JAR_FAM" "$JAR_ANON" "$JAR_COPIA"
  unset PASS PASS_FAM CODIGO INV_TOKEN CUERPO_LOGIN
  if [ "$CREO" = 1 ] && [ "$AYUDA_MOSTRADA" = 0 ]; then ayuda_limpieza; fi
}
trap terminar EXIT
trap 'echo; echo "Interrumpido."; exit 130' INT TERM

ok()   { echo "  [OK]    [$PASO] $1"; }
fail() {
  echo "  [FALLA] [$PASO] $1"; FALLOS=$((FALLOS + 1))
  if [ "$SEGUIR" != 1 ]; then echo; echo "SMOKE FALLO EN EL PASO $PASO: $PASO_DESC"; exit 1; fi
}
paso() { PASO="$1"; PASO_DESC="$2"; echo "== $1. $2"; }

como() { case "$1" in admin) JAR="$JAR_ADMIN";; familia) JAR="$JAR_FAM";; *) JAR="$JAR_ANON";; esac; }

csrf() {
  curl -s --max-time 15 -b "$JAR" -c "$JAR" "$BASE/api/auth/csrf" -o /dev/null
  TOKEN="$(awk '$6=="XSRF-TOKEN"{print $7}' "$JAR" | tail -n1)"
}

# enviar <METODO> <ruta> [json]  -> deja CUERPO y ESTADO (el cuerpo viaja por stdin: no queda en la lista de procesos)
enviar() {
  local metodo="$1" ruta="$2" cuerpo="${3:-}" salida
  local args=(-s --max-time 20 -b "$JAR" -c "$JAR" -X "$metodo" -w '\n%{http_code}')
  [ "$metodo" != GET ] && args+=(-H "X-XSRF-TOKEN: $TOKEN")
  if [ -n "$cuerpo" ]; then
    salida="$(printf '%s' "$cuerpo" | curl "${args[@]}" -H 'Content-Type: application/json' --data-binary @- "$BASE$ruta")"
  else
    salida="$(curl "${args[@]}" "$BASE$ruta")"
  fi
  ESTADO="${salida##*$'\n'}"; CUERPO="${salida%$'\n'*}"
}
get()  { enviar GET "$1"; }
post() { enviar POST "$1" "${2:-}"; }

campo() { python3 -c 'import sys,json
try:
    d=json.load(sys.stdin)
    for k in sys.argv[1].split("."):
        d=d[k]
    print(str(d).lower() if isinstance(d,bool) else d)
except Exception:
    print("")' "$1"; }

# jx <expresion python sobre d> [args...]  (a = args)  -> imprime el resultado sobre CUERPO; "" si falla
jx() { printf '%s' "$CUERPO" | python3 -c 'import sys,json
try:
    d=json.load(sys.stdin)
    r=eval(sys.argv[1],{"d":d,"a":sys.argv[2:]})
    print(str(r).lower() if isinstance(r,bool) else r)
except Exception:
    print("")' "$@"; }

jobj() { python3 -c 'import sys,json;a=sys.argv[1:];print(json.dumps(dict(zip(a[::2],a[1::2]))))' "$@"; }

esperar() { # esperar <estado-esperado> <descripcion>
  if [ "$ESTADO" = "$1" ]; then ok "$2 (HTTP $ESTADO)"
  else fail "$2: esperado $1, recibido $ESTADO (codigo: $(echo "$CUERPO" | campo codigo))"; fi
}
esperar_error() { # esperar_error <estado> <codigo> <descripcion>
  local cod; cod="$(echo "$CUERPO" | campo codigo)"
  if [ "$ESTADO" = "$1" ] && [ "$cod" = "$2" ]; then ok "$3 (HTTP $ESTADO, codigo $cod)"
  else fail "$3: esperado $1/$2, recibido $ESTADO (codigo: $cod)"; fi
}
igual() { # igual <descripcion> <actual> <esperado>
  if [ "$2" = "$3" ]; then ok "$1"; else fail "$1 (esperado '$3', obtenido '$2')"; fi
}
corto() { echo "${1:0:6}"; }
aleatorio() { python3 -c 'import secrets,string;print("".join(secrets.choice(string.ascii_lowercase+string.digits) for _ in range(int(__import__("sys").argv[1]))))' "$1"; }
gen_dni() { python3 -c 'import secrets;print("9"+"".join(secrets.choice("0123456789") for _ in range(8)))'; }

# crear_deportista <etiqueta> -> deja DEP_ID (y DEP_DNI); reintenta una vez ante 409 de DNI
crear_deportista() {
  local intento cuerpo cod
  for intento in 1 2; do
    DEP_DNI="$(gen_dni)"
    cuerpo="$(jobj dni "$DEP_DNI" nombre "${RUN}_$1" apellido "$RUN" fechaNacimiento 2012-05-10 nacionalidad Argentina)"
    como admin; csrf; post /api/admin/deportistas "$cuerpo"
    cod="$(echo "$CUERPO" | campo codigo)"
    if [ "$ESTADO" = 409 ] && { [ "$cod" = DNI_DUPLICADO ] || [ "$cod" = DNI_RESERVADO_POR_INACTIVO ]; }; then continue; fi
    break
  done
  esperar 201 "crear deportista $1"
  DEP_ID="$(echo "$CUERPO" | campo id)"
}

# ---------------------------------------------------------------------------------------------------------------------------
RUN="SMOKE_$(date +%Y%m%d%H%M%S)_$(aleatorio 4)"
EMAIL_FAM="smoke_${RUN#SMOKE_}@example.test"
echo "Smoke de familias contra $BASE"
echo "Run id: $RUN   (todo lo que se crea lleva este prefijo)"
echo

read -r -p "Email ADMIN: " EMAIL
read -r -s -p "Password ADMIN: " PASS; echo
paso 1 "Login ADMIN + MFA"
como admin; csrf
CUERPO_LOGIN="$(printf '%s\n%s' "$EMAIL" "$PASS" | python3 -c 'import sys,json;e=sys.stdin.readline().rstrip("\n");p=sys.stdin.readline().rstrip("\n");print(json.dumps({"email":e,"password":p}))')"
unset PASS
post /api/auth/admin/login "$CUERPO_LOGIN"; unset CUERPO_LOGIN
esperar 200 "login ADMIN (sesion MFA pendiente)"
igual "mfaPendiente=true tras el login" "$(echo "$CUERPO" | campo mfaPendiente)" true
ENROLADO="$(echo "$CUERPO" | campo mfaEnrolado)"
csrf
if [ "$ENROLADO" != "true" ]; then
  echo "  ATENCION: esta cuenta ADMIN todavia NO tiene MFA enrolado. Para seguir hay que ENROLARLO ahora (cambia el estado real de la cuenta)"
  echo "  y se imprimira el secreto TOTP UNA sola vez en pantalla, para que lo cargues en tu app autenticadora."
  read -r -p "  Enter para enrolar, Ctrl-C para cancelar: " _
  post /api/auth/admin/mfa/enrolar
  esperar 200 "enrolar TOTP"
  echo "  Agrega este secreto a tu app autenticadora (Base32): $(echo "$CUERPO" | campo secretoBase32)"
  echo "  o escanea/pega el URI: $(echo "$CUERPO" | campo otpauthUri)"
  read -r -s -p "Codigo TOTP de 6 digitos: " CODIGO; echo
  csrf; post /api/auth/admin/mfa/confirmar "$(jobj codigo "$CODIGO")"
  esperar 200 "confirmar TOTP"
else
  read -r -s -p "Codigo TOTP de 6 digitos: " CODIGO; echo
  post /api/auth/admin/mfa/verificar "$(jobj codigo "$CODIGO")"
  esperar 200 "verificar TOTP"
fi
unset CODIGO
csrf; get /api/auth/me
esperar 200 "GET /api/auth/me"
igual "rol=ADMIN" "$(echo "$CUERPO" | campo rol)" ADMIN
igual "mfaPendiente=false (sesion completa)" "$(echo "$CUERPO" | campo mfaPendiente)" false

paso 2 "Crear la familia A"
CREO=1
csrf; post /api/admin/familias "$(jobj nombreReferencia "${RUN}_A")"
esperar 201 "POST /api/admin/familias"
FAM_A="$(echo "$CUERPO" | campo id)"
igual "familia A activa" "$(echo "$CUERPO" | campo activa)" true
echo "         familia A: $(corto "$FAM_A")"

paso 3 "Crear un tutor en la familia A"
csrf
post "/api/admin/familias/$FAM_A/tutores" "$(jobj nombre "${RUN}_T" apellido "$RUN" dni "$(gen_dni)" telefono "11 5555 0000" email "smoke_tutor_${RUN#SMOKE_}@example.test" parentesco Madre)"
esperar 201 "POST /api/admin/familias/{A}/tutores"
TUTOR_ID="$(echo "$CUERPO" | campo id)"
igual "el tutor pertenece a la familia A" "$(echo "$CUERPO" | campo familiaId)" "$FAM_A"

paso 4 "Crear 2 deportistas (D1, D2)"
crear_deportista D1; D1="$DEP_ID"; DNI_D1="$DEP_DNI"
crear_deportista D2; D2="$DEP_ID"
echo "         D1: $(corto "$D1")  D2: $(corto "$D2")"
csrf; post /api/admin/deportistas "$(jobj dni "$DNI_D1" nombre "${RUN}_DUP" apellido "$RUN")"
esperar_error 409 DNI_DUPLICADO "control negativo: DNI repetido de un deportista activo"
unset DNI_D1 DEP_DNI

paso 5 "Vincular D1 y D2 a la familia A en UNA llamada (lote)"
csrf; post "/api/admin/familias/$FAM_A/deportistas" "{\"deportistaIds\":[\"$D1\",\"$D2\"]}"
esperar 200 "POST /api/admin/familias/{A}/deportistas"
igual "2 resultados" "$(jx 'len(d["resultados"])')" 2
igual "ambos CREADO" "$(jx 'all(r["resultado"]=="CREADO" for r in d["resultados"])')" true
igual "orden pedido (D1, D2)" "$(jx '[r["vinculo"]["deportistaId"] for r in d["resultados"]]==[a[0],a[1]]' "$D1" "$D2")" true
igual "sin otro principal, cada deportista queda con su vinculo principal" "$(jx 'all(r["vinculo"]["esPrincipal"] for r in d["resultados"])')" true
csrf; post "/api/admin/familias/$FAM_A/deportistas" "{\"deportistaIds\":[\"$D1\"]}"
esperar 200 "control negativo: vincular de nuevo a D1"
igual "D1 ya vinculado: SIN_CAMBIOS" "$(jx 'd["resultados"][0]["resultado"]')" SIN_CAMBIOS

paso 6 "Exactamente un vinculo ACTIVO principal por deportista (GET de vinculos)"
get "/api/admin/familias/$FAM_A/deportistas"
esperar 200 "GET /api/admin/familias/{A}/deportistas"
igual "2 vinculos ACTIVOS en la familia A" "$(jx 'sum(1 for v in d if v["estado"]=="ACTIVO")')" 2
for DEP in "$D1" "$D2"; do
  get "/api/admin/deportistas/$DEP/familias"
  esperar 200 "GET /api/admin/deportistas/$(corto "$DEP")/familias"
  igual "deportista $(corto "$DEP"): exactamente un ACTIVO principal" "$(jx 'sum(1 for v in d if v["estado"]=="ACTIVO" and v["esPrincipal"])')" 1
  igual "deportista $(corto "$DEP"): el principal es la familia A" "$(jx '[v["familiaId"] for v in d if v["estado"]=="ACTIVO" and v["esPrincipal"]]==[a[0]]' "$FAM_A")" true
done

paso 7 "Cambiar el principal de D1 de la familia A a la familia B (operacion explicita)"
echo "         (el principal es por deportista: se necesita una segunda familia SMOKE_ que tambien tenga a D1)"
csrf; post /api/admin/familias "$(jobj nombreReferencia "${RUN}_B")"
esperar 201 "crear la familia B"
FAM_B="$(echo "$CUERPO" | campo id)"
crear_deportista D3; D3="$DEP_ID"
csrf; post "/api/admin/familias/$FAM_B/deportistas" "{\"deportistaIds\":[\"$D3\"]}"
esperar 200 "vincular D3 solo a la familia B (sirve para el paso 13)"
csrf; post "/api/admin/familias/$FAM_B/deportistas" "{\"deportistaIds\":[\"$D1\"]}"
esperar 200 "vincular D1 tambien a la familia B"
igual "D1 en B: CREADO" "$(jx 'd["resultados"][0]["resultado"]')" CREADO
igual "D1 en B: no es principal (A ya lo es)" "$(jx 'd["resultados"][0]["vinculo"]["esPrincipal"]')" false
get "/api/admin/deportistas/$D1/familias"
igual "D1: un solo principal, sigue siendo A" "$(jx '[v["familiaId"] for v in d if v["estado"]=="ACTIVO" and v["esPrincipal"]]==[a[0]]' "$FAM_A")" true
csrf; post "/api/admin/familias/$FAM_B/deportistas/$D1/principal"
esperar 200 "POST .../familias/{B}/deportistas/{D1}/principal"
get "/api/admin/deportistas/$D1/familias"
igual "D1: exactamente un ACTIVO principal" "$(jx 'sum(1 for v in d if v["estado"]=="ACTIVO" and v["esPrincipal"])')" 1
igual "D1: el principal se movio a la familia B" "$(jx '[v["familiaId"] for v in d if v["estado"]=="ACTIVO" and v["esPrincipal"]]==[a[0]]' "$FAM_B")" true
igual "D1: el vinculo con A sigue ACTIVO y dejo de ser principal" "$(jx 'any(v["familiaId"]==a[0] and v["estado"]=="ACTIVO" and not v["esPrincipal"] for v in d)' "$FAM_A")" true

paso 8 "Crear una invitacion para la familia A"
csrf; post /api/admin/invitaciones "{\"familiaId\":\"$FAM_A\",\"emailSugerido\":\"$EMAIL_FAM\",\"diasVigencia\":1}"
esperar 201 "POST /api/admin/invitaciones"
INV_ID="$(echo "$CUERPO" | campo id)"
INV_TOKEN="$(echo "$CUERPO" | campo token)"
[ -n "$INV_ID" ] && [ -n "$INV_TOKEN" ] && ok "respuesta con id y token (el token no se imprime)" || fail "respuesta sin id/token"
igual "estado=PENDIENTE" "$(echo "$CUERPO" | campo estado)" PENDIENTE
igual "la invitacion es de la familia A" "$(echo "$CUERPO" | campo familia.id)" "$FAM_A"

paso 9 "Registrar un usuario FAMILIA con la invitacion (sin sesion)"
PASS_FAM="Smk-$(aleatorio 16)"
como anon; csrf
post /api/auth/registro/invitacion "$(jobj token "$INV_TOKEN" nombre "$RUN" apellido "$RUN" email "$EMAIL_FAM" password "$PASS_FAM")"
esperar 201 "POST /api/auth/registro/invitacion"
igual "rol=FAMILIA" "$(echo "$CUERPO" | campo rol)" FAMILIA
igual "familiaId=A" "$(echo "$CUERPO" | campo familiaId)" "$FAM_A"
igual "el registro no emite cookie de sesion" "$(awk '$6=="BP_SESION"' "$JAR_ANON" | wc -l | tr -d ' ')" 0
csrf; post /api/auth/registro/invitacion "$(jobj token "$INV_TOKEN" nombre "$RUN" apellido "$RUN" email "$EMAIL_FAM" password "$PASS_FAM")"
esperar_error 400 INVITACION_NO_DISPONIBLE "control negativo: reutilizar la invitacion"
unset INV_TOKEN

paso 10 "Login FAMILIA"
como familia; csrf
post /api/auth/login "$(jobj email "$EMAIL_FAM" password "$PASS_FAM")"
unset PASS_FAM
esperar 200 "POST /api/auth/login (FAMILIA)"
igual "rol=FAMILIA" "$(echo "$CUERPO" | campo rol)" FAMILIA
igual "familiaId=A" "$(echo "$CUERPO" | campo familiaId)" "$FAM_A"
csrf; get /api/auth/me
esperar 200 "GET /api/auth/me (FAMILIA)"
igual "mfaPendiente=false" "$(echo "$CUERPO" | campo mfaPendiente)" false

paso 11 "Portal: mi-familia"
get /api/familia/mi-familia
esperar 200 "GET /api/familia/mi-familia"
igual "es la familia A" "$(echo "$CUERPO" | campo id)" "$FAM_A"
igual "un tutor (el creado por el ADMIN)" "$(jx 'len(d["tutores"])')" 1
igual "el tutor es el creado en el paso 3" "$(jx 'd["tutores"][0]["id"]')" "$TUTOR_ID"
igual "el tutor no incluye DNI (solo id, nombre, apellido, parentesco, telefono, email)" "$(jx 'sorted(d["tutores"][0].keys())==sorted(["id","nombre","apellido","parentesco","telefono","email"])')" true

paso 12 "Portal: listado y detalle de los deportistas de la familia A"
get /api/familia/deportistas
esperar 200 "GET /api/familia/deportistas"
igual "totalElementos=2" "$(jx 'd["totalElementos"]')" 2
igual "ve D1 (aunque su principal sea B) y D2" "$(jx 'sorted(x["id"] for x in d["contenido"])==sorted([a[0],a[1]])' "$D1" "$D2")" true
igual "campos del listado = id, nombre, apellido, fechaNacimiento, activo (sin escuelaId ni esPrincipal)" "$(jx 'all(sorted(x.keys())==sorted(["id","nombre","apellido","fechaNacimiento","activo"]) for x in d["contenido"])')" true
igual "ambos activos" "$(jx 'all(x["activo"] for x in d["contenido"])')" true
get "/api/familia/deportistas/$D1"
esperar 200 "GET /api/familia/deportistas/{D1}"
igual "campos del detalle completos y sin escuelaId/esPrincipal" "$(jx 'sorted(d.keys())==sorted(["id","nombre","apellido","dni","cuil","fechaNacimiento","nacionalidad","domicilio","otrosDatosDomicilio","localidad","partido","codigoPostal","telefonoContacto","emailFederativo","activo"])')" true
igual "el detalle es el de D1" "$(jx 'd["id"]==a[0] and d["nombre"]==a[1]' "$D1" "${RUN}_D1")" true
get "/api/familia/deportistas/$D2"
esperar 200 "GET /api/familia/deportistas/{D2}"

paso 13 "Aislamiento de FAMILIA"
ALEATORIO_ID="$(python3 -c 'import uuid;print(uuid.uuid4())')"
get "/api/familia/deportistas/$ALEATORIO_ID"
esperar_error 404 DEPORTISTA_NO_ENCONTRADO "detalle de un UUID inexistente"
CUERPO_404_A="$CUERPO"
get "/api/familia/deportistas/$D3"
esperar_error 404 DEPORTISTA_NO_ENCONTRADO "detalle de un deportista de OTRA familia (D3, familia B)"
igual "mismo cuerpo 404 para el inexistente y el ajeno" "$([ "$CUERPO_404_A" = "$CUERPO" ] && echo true || echo false)" true
get /api/admin/familias
esperar_error 403 ACCESO_DENEGADO "FAMILIA no accede a /api/admin/**"
get /api/admin/deportistas
esperar_error 403 ACCESO_DENEGADO "FAMILIA no accede a /api/admin/deportistas"
csrf; post /api/familia/deportistas '{"nombre":"x"}'
esperar_error 405 METODO_NO_PERMITIDO "escritura (POST con CSRF) en /api/familia/**"
csrf; enviar DELETE "/api/familia/deportistas/$D1"
esperar_error 405 METODO_NO_PERMITIDO "DELETE en /api/familia/** (con CSRF)"
como anon
get /api/familia/mi-familia
esperar_error 401 NO_AUTENTICADO "sin sesion no accede al portal"

paso 14 "Regla del deportista inactivo y del vinculo revocado"
como admin; csrf; post "/api/admin/deportistas/$D2/desactivar"
esperar 200 "desactivar D2"
igual "D2 activo=false" "$(echo "$CUERPO" | campo activo)" false
como familia; get /api/familia/deportistas
esperar 200 "listado de la FAMILIA tras desactivar D2"
igual "la FAMILIA sigue viendo a D2 (vinculo ACTIVO) con activo=false en el listado" "$(jx 'any(x["id"]==a[0] and x["activo"]==False for x in d["contenido"])' "$D2")" true
igual "el listado sigue con 2 deportistas" "$(jx 'd["totalElementos"]')" 2
get "/api/familia/deportistas/$D2"
esperar 200 "detalle de D2 inactivo"
igual "detalle de D2 con activo=false" "$(echo "$CUERPO" | campo activo)" false
como admin; csrf; post "/api/admin/familias/$FAM_A/deportistas/$D2/revocar"
esperar 200 "revocar el vinculo de D2 con la familia A"
igual "vinculo REVOCADO y sin principal" "$(jx 'd["estado"]=="REVOCADO" and d["esPrincipal"]==False')" true
como familia; get "/api/familia/deportistas/$D2"
esperar_error 404 DEPORTISTA_NO_ENCONTRADO "detalle de D2 con el vinculo revocado"
get /api/familia/deportistas
igual "D2 ya no esta en el listado (queda solo D1)" "$(jx 'd["totalElementos"]==1 and d["contenido"][0]["id"]==a[0]' "$D1")" true

paso "14b" "Revalidacion central: familia inactiva => 401, reactivada => la misma sesion vuelve a funcionar"
cp "$JAR_FAM" "$JAR_COPIA"
como admin; csrf; post "/api/admin/familias/$FAM_A/desactivar"
esperar 200 "desactivar la familia A"
# La respuesta 401 borra la cookie del jar usado: se prueba con una copia para poder verificar despues que la sesion (el JWT) sigue valida.
JAR="$JAR_COPIA"; get /api/familia/mi-familia
esperar_error 401 NO_AUTENTICADO "con la familia inactiva, la siguiente solicitud de la FAMILIA"
como admin; csrf; post "/api/admin/familias/$FAM_A/activar"
esperar 200 "reactivar la familia A"
como familia; get /api/familia/mi-familia
esperar 200 "la sesion original funciona de nuevo sin login"

paso 15 "Logout de FAMILIA y de ADMIN"
como familia; csrf; post /api/auth/logout
esperar 204 "logout FAMILIA"
get /api/familia/mi-familia
esperar_error 401 NO_AUTENTICADO "tras el logout la FAMILIA no accede"
como admin; csrf; post /api/auth/logout
esperar 204 "logout ADMIN"

echo
echo "Creado en esta corrida: familias A/B ($(corto "$FAM_A"), $(corto "$FAM_B")), 1 tutor, deportistas D1/D2/D3, vinculos, 1 invitacion usada y el usuario $EMAIL_FAM."
if [ "$FALLOS" -eq 0 ]; then echo "SMOKE OK"; else echo "SMOKE CON $FALLOS FALLA(S)"; fi
ayuda_limpieza
[ "$FALLOS" -eq 0 ] || exit 1
