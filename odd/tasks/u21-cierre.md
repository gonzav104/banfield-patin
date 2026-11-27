# U21 — Cierre (documentación médica y aptitud física anual)
Unidad **integrada en `main`** el 2026-11-27, en dos PR sin squash ni reescritura: backend (PR #57, merge `3e51372`) y frontend (PR #58, merge `fff7575`). Banderas `DOCUMENTOS_CARGA_HABILITADA` y `DOCUMENTOS_STORAGE_HABILITADO` apagadas por defecto; sin escrituras habilitadas en producción.

## Hechos verificados
- **Backend (PR #57):** V17 aditiva (V14–V16 intactas); metadatos del apto en `documento` y en la operación de carga con huella idempotente; estado derivado y vigencia (2026 confirmada, resto provisoria); revisión y descarga auditada; corrección ADMIN con retiro de la aprobación (trigger); privacidad entre familias por procedencia (`cargado_por → usuario.familia_id`) también para el motivo de observación. Árbol integrado idéntico al candidato (`b43eb8f`); CI posterior de `main` 8/8.
- **Frontend (PR #58):** secciones FAMILIA y ADMIN del apto, idempotencia y recuperación de resultados inciertos, `datosVisibles`, banderas, E2E reales y reparto de shards (shard 3 con la carga apagada). Árbol integrado idéntico al candidato (`224713b`); CI del PR 16/16 tras dos reintentos de pruebas intermitentes de backend ajenas al PR.
- **Revisiones Gentle AI:** backend, cuatro candidatos aprobados y reconocidos; frontend, tres aprobados y uno (60 líneas, `abcfe88`) omitido por decisión registrada del usuario.

## Condiciones pendientes antes del despliegue
1. Política de **retención y eliminación** de documentación médica vencida (plazo, borrado en Storage y en BD, auditoría).
2. Evaluar **endurecer `usuario.familia_id` y la procedencia documental** (probable V18, fuera de este ciclo).
3. Regla de 2027 sin confirmar (se muestra siempre como provisoria).

## Limitaciones conocidas
- El portal lista temporadas solo hasta la activa: sin temporada activa, o para una posterior, la familia no puede cargar el apto.
- Pruebas intermitentes de backend (UUID aleatorio + texto prohibido; sesión bloqueada elegida con `get(0)`): PR de pruebas independiente en curso.

## Próximas unidades
**U21-BIS — Autoservicio y empadronamiento familiar** (diseño en `docs/U21-BIS-AUTOSERVICIO-FAMILIAR.md`, pendiente de aprobación; antes de U22). **U22** no se inicia.
