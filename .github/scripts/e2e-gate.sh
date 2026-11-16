#!/usr/bin/env bash
# Compuerta del control protegido «E2E reales (backend + PostgreSQL descartable)».
#
# Recibe el resultado AGREGADO de los jobs de shard (`needs.e2e-shard.result` de GitHub Actions: «success» solo si TODAS las ramas
# de la matriz terminaron en success) y termina con exito unicamente en ese caso. Cualquier otro valor -- failure, cancelled, skipped
# o uno desconocido o vacio -- falla: un shard fallido, cancelado u omitido nunca puede dejar el control en verde. Se prueba aparte
# (`bash .github/scripts/e2e-gate.sh <resultado>`); no hace falta GitHub para ejercitarlo.
set -euo pipefail

result="${1:-}"
if [ "$result" = "success" ]; then
  echo "Todos los shards de E2E reales terminaron en success."
  exit 0
fi
echo "::error title=E2E reales::Los shards de E2E reales no terminaron todos en success (resultado: '${result:-vacío}'). El control protegido no puede quedar en verde." >&2
exit 1
