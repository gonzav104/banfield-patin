-- Banfield Patín Carrera / Plataforma de gestión deportiva
-- Migración inicial PostgreSQL 15+ / Supabase
-- Diseñada para ejecutarse con Flyway.
--
-- Decisiones:
--   * Spring Boot es la única capa que accede a los datos de negocio.
--   * Supabase se usa como PostgreSQL administrado y Storage privado.
--   * Los binarios NO se guardan en PostgreSQL; solo sus metadatos/storage_key.
--   * Las tablas viven en un esquema propio para no exponerlas accidentalmente
--     mediante la API REST pública de Supabase.

-- Flyway ejecuta cada migración dentro de su propia transacción: no usar BEGIN/COMMIT.
-- gen_random_uuid() es nativa desde PostgreSQL 13, por lo que no se requiere pgcrypto.

CREATE SCHEMA IF NOT EXISTS gestion_patin;

-- =========================================================
-- 1. ESCUELAS / TENANCY
-- =========================================================

CREATE TABLE gestion_patin.escuela (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre                  varchar(150) NOT NULL,
    nombre_federativo       varchar(180),
    slug                    varchar(80) NOT NULL,
    email_contacto          varchar(180),
    telefono_contacto       varchar(40),
    direccion               varchar(250),
    activa                  boolean NOT NULL DEFAULT true,
    max_administradores     smallint NOT NULL DEFAULT 2 CHECK (max_administradores > 0),
    limite_archivo_mb       smallint NOT NULL DEFAULT 15 CHECK (limite_archivo_mb >= 15),
    creado_en               timestamptz NOT NULL DEFAULT now(),
    actualizado_en          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_escuela_id_tenant UNIQUE (id)
);

CREATE UNIQUE INDEX uq_escuela_slug
    ON gestion_patin.escuela (lower(slug));

-- =========================================================
-- 2. FAMILIAS, USUARIOS Y TUTORES
-- =========================================================

CREATE TABLE gestion_patin.familia (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id          uuid NOT NULL,
    nombre_referencia   varchar(150),
    activa              boolean NOT NULL DEFAULT true,
    creado_en           timestamptz NOT NULL DEFAULT now(),
    actualizado_en      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_familia_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT uq_familia_id_escuela UNIQUE (id, escuela_id)
);

CREATE TABLE gestion_patin.usuario (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id          uuid NOT NULL,
    familia_id          uuid,
    nombre              varchar(100) NOT NULL,
    apellido            varchar(100) NOT NULL,
    email               varchar(180) NOT NULL,
    password_hash       varchar(255) NOT NULL,
    rol                 varchar(20) NOT NULL,
    email_verificado    boolean NOT NULL DEFAULT false,
    mfa_habilitado      boolean NOT NULL DEFAULT false,
    activo              boolean NOT NULL DEFAULT true,
    ultimo_acceso_en    timestamptz,
    creado_en           timestamptz NOT NULL DEFAULT now(),
    actualizado_en      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_usuario_rol
        CHECK (rol IN ('ADMIN', 'FAMILIA')),
    CONSTRAINT ck_usuario_familia_por_rol
        CHECK (
            (rol = 'ADMIN' AND familia_id IS NULL)
            OR
            (rol = 'FAMILIA' AND familia_id IS NOT NULL)
        ),
    CONSTRAINT fk_usuario_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_usuario_familia_misma_escuela
        FOREIGN KEY (familia_id, escuela_id)
        REFERENCES gestion_patin.familia(id, escuela_id),
    CONSTRAINT uq_usuario_id_escuela UNIQUE (id, escuela_id)
);

CREATE UNIQUE INDEX uq_usuario_email_escuela
    ON gestion_patin.usuario (escuela_id, lower(email));

CREATE TABLE gestion_patin.tutor (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id          uuid NOT NULL,
    familia_id          uuid NOT NULL,
    usuario_id          uuid,
    nombre              varchar(100) NOT NULL,
    apellido            varchar(100) NOT NULL,
    dni                 varchar(20),
    telefono            varchar(40),
    email               varchar(180),
    parentesco          varchar(40),
    activo              boolean NOT NULL DEFAULT true,
    creado_en           timestamptz NOT NULL DEFAULT now(),
    actualizado_en      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_tutor_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_tutor_familia_misma_escuela
        FOREIGN KEY (familia_id, escuela_id)
        REFERENCES gestion_patin.familia(id, escuela_id),
    CONSTRAINT fk_tutor_usuario_misma_escuela
        FOREIGN KEY (usuario_id, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id),
    CONSTRAINT uq_tutor_id_escuela UNIQUE (id, escuela_id)
);

CREATE UNIQUE INDEX uq_tutor_usuario
    ON gestion_patin.tutor(usuario_id)
    WHERE usuario_id IS NOT NULL;

-- =========================================================
-- 3. DEPORTISTAS Y TEMPORADAS
-- =========================================================

CREATE TABLE gestion_patin.deportista (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id              uuid NOT NULL,
    nombre                  varchar(100) NOT NULL,
    apellido                varchar(100) NOT NULL,
    dni                     varchar(20) NOT NULL,
    cuil                    varchar(20),
    fecha_nacimiento        date,
    nacionalidad            varchar(80),
    domicilio               varchar(180),
    otros_datos_domicilio   varchar(250),
    localidad               varchar(100),
    partido                 varchar(100),
    codigo_postal           varchar(15),
    telefono_contacto       varchar(40),
    email_federativo        varchar(180),
    activo                  boolean NOT NULL DEFAULT true,
    creado_en               timestamptz NOT NULL DEFAULT now(),
    actualizado_en          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_deportista_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT uq_deportista_id_escuela UNIQUE (id, escuela_id)
);

CREATE UNIQUE INDEX uq_deportista_dni_escuela
    ON gestion_patin.deportista(escuela_id, dni);

CREATE UNIQUE INDEX uq_deportista_cuil_escuela
    ON gestion_patin.deportista(escuela_id, cuil)
    WHERE cuil IS NOT NULL;

CREATE INDEX ix_deportista_busqueda_nombre
    ON gestion_patin.deportista(escuela_id, apellido, nombre);

CREATE TABLE gestion_patin.familia_deportista (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id          uuid NOT NULL,
    familia_id          uuid NOT NULL,
    deportista_id       uuid NOT NULL,
    estado              varchar(20) NOT NULL DEFAULT 'PENDIENTE',
    es_principal        boolean NOT NULL DEFAULT true,
    autorizado_por     uuid,
    autorizado_en      timestamptz,
    creado_en           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_familia_deportista_estado
        CHECK (estado IN ('PENDIENTE', 'ACTIVO', 'RECHAZADO', 'REVOCADO')),
    CONSTRAINT fk_fd_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_fd_familia_misma_escuela
        FOREIGN KEY (familia_id, escuela_id)
        REFERENCES gestion_patin.familia(id, escuela_id),
    CONSTRAINT fk_fd_deportista_misma_escuela
        FOREIGN KEY (deportista_id, escuela_id)
        REFERENCES gestion_patin.deportista(id, escuela_id),
    CONSTRAINT fk_fd_autorizado_por_misma_escuela
        FOREIGN KEY (autorizado_por, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id),
    CONSTRAINT uq_familia_deportista UNIQUE (familia_id, deportista_id)
);

CREATE INDEX ix_fd_deportista
    ON gestion_patin.familia_deportista(deportista_id, estado);

CREATE TABLE gestion_patin.temporada (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id          uuid NOT NULL,
    anio                smallint NOT NULL CHECK (anio BETWEEN 2020 AND 2100),
    activa              boolean NOT NULL DEFAULT false,
    creado_en           timestamptz NOT NULL DEFAULT now(),
    actualizado_en      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_temporada_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT uq_temporada_escuela_anio UNIQUE (escuela_id, anio),
    CONSTRAINT uq_temporada_id_escuela UNIQUE (id, escuela_id)
);

CREATE UNIQUE INDEX uq_temporada_activa_por_escuela
    ON gestion_patin.temporada(escuela_id)
    WHERE activa = true;

CREATE TABLE gestion_patin.deportista_temporada (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id          uuid NOT NULL,
    deportista_id       uuid NOT NULL,
    temporada_id        uuid NOT NULL,
    categoria           varchar(80),
    rama                varchar(40),
    divisional          varchar(80),
    numero_corredor     varchar(30),
    estado_deportivo    varchar(20) NOT NULL DEFAULT 'ACTIVO',
    creado_en           timestamptz NOT NULL DEFAULT now(),
    actualizado_en      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_deportista_temporada_estado
        CHECK (estado_deportivo IN ('ACTIVO', 'INACTIVO', 'SUSPENDIDO')),
    CONSTRAINT fk_dt_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_dt_deportista_misma_escuela
        FOREIGN KEY (deportista_id, escuela_id)
        REFERENCES gestion_patin.deportista(id, escuela_id),
    CONSTRAINT fk_dt_temporada_misma_escuela
        FOREIGN KEY (temporada_id, escuela_id)
        REFERENCES gestion_patin.temporada(id, escuela_id),
    CONSTRAINT uq_deportista_temporada UNIQUE (deportista_id, temporada_id),
    CONSTRAINT uq_dt_id_escuela UNIQUE (id, escuela_id)
);

CREATE UNIQUE INDEX uq_numero_corredor_por_temporada
    ON gestion_patin.deportista_temporada(escuela_id, temporada_id, numero_corredor)
    WHERE numero_corredor IS NOT NULL;

-- =========================================================
-- 4. DOCUMENTOS, APTOS Y FEDERACIÓN
-- =========================================================

CREATE TABLE gestion_patin.documento (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id              uuid NOT NULL,
    deportista_id           uuid,
    tutor_id                uuid,
    temporada_id            uuid,
    tipo                    varchar(50) NOT NULL,
    nombre_original         varchar(255) NOT NULL,
    storage_bucket          varchar(100) NOT NULL DEFAULT 'documentos-privados',
    storage_key             varchar(500) NOT NULL,
    mime_type               varchar(100) NOT NULL,
    tamanio_bytes           bigint NOT NULL CHECK (tamanio_bytes > 0),
    estado                  varchar(25) NOT NULL DEFAULT 'CARGADO',
    motivo_observacion      varchar(500),
    fecha_vencimiento       date,
    vigente                 boolean NOT NULL DEFAULT true,
    documento_anterior_id   uuid,
    cargado_por             uuid NOT NULL,
    revisado_por            uuid,
    revisado_en             timestamptz,
    creado_en               timestamptz NOT NULL DEFAULT now(),
    actualizado_en          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_documento_propietario
        CHECK (num_nonnulls(deportista_id, tutor_id) = 1),
    CONSTRAINT ck_documento_tipo
        CHECK (tipo IN (
            'DNI_DEPORTISTA_FRENTE',
            'DNI_DEPORTISTA_DORSO',
            'DNI_TUTOR_FRENTE',
            'DNI_TUTOR_DORSO',
            'APTO_FISICO',
            'SOLICITUD_LICENCIA',
            'CERTIFICADO_WADA',
            'OTRO'
        )),
    CONSTRAINT ck_documento_estado
        CHECK (estado IN ('PENDIENTE', 'CARGADO', 'APROBADO', 'OBSERVADO', 'VENCIDO')),
    CONSTRAINT ck_documento_observado_con_motivo
        CHECK (estado <> 'OBSERVADO' OR motivo_observacion IS NOT NULL),
    CONSTRAINT fk_documento_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_documento_deportista_misma_escuela
        FOREIGN KEY (deportista_id, escuela_id)
        REFERENCES gestion_patin.deportista(id, escuela_id),
    CONSTRAINT fk_documento_tutor_misma_escuela
        FOREIGN KEY (tutor_id, escuela_id)
        REFERENCES gestion_patin.tutor(id, escuela_id),
    CONSTRAINT fk_documento_temporada_misma_escuela
        FOREIGN KEY (temporada_id, escuela_id)
        REFERENCES gestion_patin.temporada(id, escuela_id),
    CONSTRAINT fk_documento_cargado_por_misma_escuela
        FOREIGN KEY (cargado_por, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id),
    CONSTRAINT fk_documento_revisado_por_misma_escuela
        FOREIGN KEY (revisado_por, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id),
    CONSTRAINT fk_documento_anterior
        FOREIGN KEY (documento_anterior_id)
        REFERENCES gestion_patin.documento(id),
    CONSTRAINT uq_documento_id_escuela UNIQUE (id, escuela_id),
    CONSTRAINT uq_documento_storage_key UNIQUE (storage_bucket, storage_key)
);

CREATE INDEX ix_documento_deportista
    ON gestion_patin.documento(escuela_id, deportista_id, tipo, vigente);

CREATE INDEX ix_documento_tutor
    ON gestion_patin.documento(escuela_id, tutor_id, tipo, vigente);

CREATE INDEX ix_documento_estado
    ON gestion_patin.documento(escuela_id, estado);

CREATE TABLE gestion_patin.apto_fisico (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id          uuid NOT NULL,
    deportista_id       uuid NOT NULL,
    temporada_id        uuid NOT NULL,
    documento_id        uuid,
    fecha_emision       date,
    medico_nombre       varchar(180),
    medico_matricula    varchar(50),
    estado              varchar(30) NOT NULL DEFAULT 'PENDIENTE',
    fecha_presentacion  timestamptz,
    presentado_por     uuid,
    observaciones       varchar(500),
    creado_en           timestamptz NOT NULL DEFAULT now(),
    actualizado_en      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_apto_estado
        CHECK (estado IN (
            'PENDIENTE', 'CARGADO', 'APROBADO', 'OBSERVADO',
            'LISTO_PARA_PRESENTAR', 'PRESENTADO'
        )),
    CONSTRAINT fk_apto_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_apto_deportista_misma_escuela
        FOREIGN KEY (deportista_id, escuela_id)
        REFERENCES gestion_patin.deportista(id, escuela_id),
    CONSTRAINT fk_apto_temporada_misma_escuela
        FOREIGN KEY (temporada_id, escuela_id)
        REFERENCES gestion_patin.temporada(id, escuela_id),
    CONSTRAINT fk_apto_documento_misma_escuela
        FOREIGN KEY (documento_id, escuela_id)
        REFERENCES gestion_patin.documento(id, escuela_id),
    CONSTRAINT fk_apto_presentado_por_misma_escuela
        FOREIGN KEY (presentado_por, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id),
    CONSTRAINT uq_apto_deportista_temporada UNIQUE (deportista_id, temporada_id),
    CONSTRAINT uq_apto_documento UNIQUE (documento_id),
    CONSTRAINT uq_apto_id_escuela UNIQUE (id, escuela_id)
);

CREATE TABLE gestion_patin.empadronamiento (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id          uuid NOT NULL,
    deportista_id       uuid NOT NULL,
    temporada_id        uuid NOT NULL,
    estado              varchar(30) NOT NULL DEFAULT 'PENDIENTE',
    fecha_presentacion  timestamptz,
    fecha_confirmacion  timestamptz,
    observaciones       varchar(500),
    creado_en           timestamptz NOT NULL DEFAULT now(),
    actualizado_en      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_empadronamiento_estado
        CHECK (estado IN (
            'PENDIENTE', 'LISTO_PARA_PRESENTAR',
            'PRESENTADO', 'EMPADRONADO', 'OBSERVADO'
        )),
    CONSTRAINT fk_empadronamiento_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_empadronamiento_deportista_misma_escuela
        FOREIGN KEY (deportista_id, escuela_id)
        REFERENCES gestion_patin.deportista(id, escuela_id),
    CONSTRAINT fk_empadronamiento_temporada_misma_escuela
        FOREIGN KEY (temporada_id, escuela_id)
        REFERENCES gestion_patin.temporada(id, escuela_id),
    CONSTRAINT uq_empadronamiento_deportista_temporada UNIQUE (deportista_id, temporada_id),
    CONSTRAINT uq_empadronamiento_id_escuela UNIQUE (id, escuela_id)
);

CREATE TABLE gestion_patin.licencia (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id              uuid NOT NULL,
    deportista_id           uuid NOT NULL,
    temporada_id            uuid NOT NULL,
    tipo                    varchar(25),
    numero                  varchar(50),
    estado                  varchar(25) NOT NULL DEFAULT 'PENDIENTE',
    documento_solicitud_id  uuid,
    fecha_solicitud         date,
    fecha_aprobacion        date,
    observaciones           varchar(500),
    creado_en               timestamptz NOT NULL DEFAULT now(),
    actualizado_en          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_licencia_tipo
        CHECK (tipo IS NULL OR tipo IN ('REGIONAL', 'FEDERATIVA', 'PROMOCIONAL', 'NACIONAL')),
    CONSTRAINT ck_licencia_estado
        CHECK (estado IN ('PENDIENTE', 'SOLICITADA', 'APROBADA', 'OBSERVADA', 'VENCIDA')),
    CONSTRAINT fk_licencia_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_licencia_deportista_misma_escuela
        FOREIGN KEY (deportista_id, escuela_id)
        REFERENCES gestion_patin.deportista(id, escuela_id),
    CONSTRAINT fk_licencia_temporada_misma_escuela
        FOREIGN KEY (temporada_id, escuela_id)
        REFERENCES gestion_patin.temporada(id, escuela_id),
    CONSTRAINT fk_licencia_documento_misma_escuela
        FOREIGN KEY (documento_solicitud_id, escuela_id)
        REFERENCES gestion_patin.documento(id, escuela_id),
    CONSTRAINT uq_licencia_deportista_temporada UNIQUE (deportista_id, temporada_id),
    CONSTRAINT uq_licencia_id_escuela UNIQUE (id, escuela_id)
);

-- =========================================================
-- 5. COMPETENCIAS E INSCRIPCIONES
-- =========================================================

CREATE TABLE gestion_patin.competencia (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id                  uuid NOT NULL,
    temporada_id                uuid NOT NULL,
    nombre                      varchar(180) NOT NULL,
    tipo                        varchar(20) NOT NULL,
    lugar                       varchar(180) NOT NULL,
    fecha                       date NOT NULL,
    fecha_limite_confirmacion   timestamptz NOT NULL,
    descripcion                 text,
    estado                      varchar(20) NOT NULL DEFAULT 'BORRADOR',
    creado_por                  uuid NOT NULL,
    creado_en                   timestamptz NOT NULL DEFAULT now(),
    actualizado_en              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_competencia_tipo
        CHECK (tipo IN ('APM', 'NACIONAL')),
    CONSTRAINT ck_competencia_estado
        CHECK (estado IN ('BORRADOR', 'ABIERTA', 'CERRADA', 'FINALIZADA', 'CANCELADA')),
    CONSTRAINT ck_competencia_limite
        CHECK (fecha_limite_confirmacion::date <= fecha),
    CONSTRAINT fk_competencia_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_competencia_temporada_misma_escuela
        FOREIGN KEY (temporada_id, escuela_id)
        REFERENCES gestion_patin.temporada(id, escuela_id),
    CONSTRAINT fk_competencia_creado_por_misma_escuela
        FOREIGN KEY (creado_por, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id),
    CONSTRAINT uq_competencia_id_escuela UNIQUE (id, escuela_id)
);

CREATE INDEX ix_competencia_fecha
    ON gestion_patin.competencia(escuela_id, fecha);

CREATE TABLE gestion_patin.inscripcion_competencia (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id          uuid NOT NULL,
    competencia_id      uuid NOT NULL,
    deportista_id       uuid NOT NULL,
    estado              varchar(20) NOT NULL DEFAULT 'SIN_RESPONDER',
    convocado_en        timestamptz NOT NULL DEFAULT now(),
    fecha_respuesta     timestamptz,
    respondido_por      uuid,
    observaciones       varchar(500),
    creado_en           timestamptz NOT NULL DEFAULT now(),
    actualizado_en      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_inscripcion_estado
        CHECK (estado IN ('SIN_RESPONDER', 'PARTICIPA', 'NO_PARTICIPA')),
    CONSTRAINT fk_inscripcion_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_inscripcion_competencia_misma_escuela
        FOREIGN KEY (competencia_id, escuela_id)
        REFERENCES gestion_patin.competencia(id, escuela_id),
    CONSTRAINT fk_inscripcion_deportista_misma_escuela
        FOREIGN KEY (deportista_id, escuela_id)
        REFERENCES gestion_patin.deportista(id, escuela_id),
    CONSTRAINT fk_inscripcion_respondido_por_misma_escuela
        FOREIGN KEY (respondido_por, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id),
    CONSTRAINT uq_inscripcion_competencia_deportista UNIQUE (competencia_id, deportista_id),
    CONSTRAINT uq_inscripcion_id_escuela UNIQUE (id, escuela_id)
);

CREATE INDEX ix_inscripcion_competencia_estado
    ON gestion_patin.inscripcion_competencia(competencia_id, estado);

-- =========================================================
-- 6. LISTAS DE BUENA FE
-- =========================================================

CREATE TABLE gestion_patin.plantilla_lbf (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id          uuid NOT NULL,
    tipo                varchar(30) NOT NULL,
    nombre              varchar(180) NOT NULL,
    version             varchar(40) NOT NULL,
    storage_bucket      varchar(100) NOT NULL DEFAULT 'plantillas-privadas',
    storage_key         varchar(500) NOT NULL,
    activa              boolean NOT NULL DEFAULT true,
    creado_en           timestamptz NOT NULL DEFAULT now(),
    actualizado_en      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_plantilla_lbf_tipo
        CHECK (tipo IN ('APM', 'NACIONAL_FEDERADOS', 'NACIONAL_INTERMEDIA')),
    CONSTRAINT fk_plantilla_lbf_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT uq_plantilla_lbf_version UNIQUE (escuela_id, tipo, version),
    CONSTRAINT uq_plantilla_lbf_id_escuela UNIQUE (id, escuela_id)
);

CREATE UNIQUE INDEX uq_plantilla_lbf_activa_por_tipo
    ON gestion_patin.plantilla_lbf(escuela_id, tipo)
    WHERE activa = true;

CREATE TABLE gestion_patin.exportacion_lbf (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id              uuid NOT NULL,
    competencia_id          uuid NOT NULL,
    plantilla_lbf_id        uuid NOT NULL,
    generado_por            uuid NOT NULL,
    cantidad_deportistas    smallint NOT NULL CHECK (cantidad_deportistas >= 0),
    storage_bucket          varchar(100),
    storage_key             varchar(500),
    creado_en               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_exportacion_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_exportacion_competencia_misma_escuela
        FOREIGN KEY (competencia_id, escuela_id)
        REFERENCES gestion_patin.competencia(id, escuela_id),
    CONSTRAINT fk_exportacion_plantilla_misma_escuela
        FOREIGN KEY (plantilla_lbf_id, escuela_id)
        REFERENCES gestion_patin.plantilla_lbf(id, escuela_id),
    CONSTRAINT fk_exportacion_generado_por_misma_escuela
        FOREIGN KEY (generado_por, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id)
);

CREATE INDEX ix_exportacion_lbf_competencia
    ON gestion_patin.exportacion_lbf(competencia_id, creado_en DESC);

-- =========================================================
-- 7. RIFAS
-- =========================================================

CREATE TABLE gestion_patin.rifa (
    id                              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id                      uuid NOT NULL,
    nombre                          varchar(180) NOT NULL,
    descripcion                     text,
    fecha_sorteo                    date NOT NULL,
    precio_numero                   numeric(12,2) NOT NULL CHECK (precio_numero >= 0),
    numero_desde                    integer NOT NULL,
    numero_hasta                    integer NOT NULL,
    cantidad_objetivo_por_familia   smallint NOT NULL DEFAULT 10 CHECK (cantidad_objetivo_por_familia > 0),
    estado                          varchar(20) NOT NULL DEFAULT 'BORRADOR',
    creado_por                      uuid NOT NULL,
    creado_en                       timestamptz NOT NULL DEFAULT now(),
    actualizado_en                  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_rifa_rango
        CHECK (numero_desde >= 0 AND numero_hasta >= numero_desde),
    CONSTRAINT ck_rifa_estado
        CHECK (estado IN ('BORRADOR', 'ACTIVA', 'CERRADA', 'SORTEADA', 'CANCELADA')),
    CONSTRAINT fk_rifa_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_rifa_creado_por_misma_escuela
        FOREIGN KEY (creado_por, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id),
    CONSTRAINT uq_rifa_id_escuela UNIQUE (id, escuela_id)
);

CREATE TABLE gestion_patin.numero_rifa (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    escuela_id          uuid NOT NULL,
    rifa_id             uuid NOT NULL,
    numero              integer NOT NULL,
    familia_id          uuid,
    estado              varchar(20) NOT NULL DEFAULT 'DISPONIBLE',
    vendido_en          timestamptz,
    actualizado_por     uuid,
    creado_en           timestamptz NOT NULL DEFAULT now(),
    actualizado_en      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_numero_rifa_estado
        CHECK (estado IN ('DISPONIBLE', 'ASIGNADO', 'VENDIDO')),
    CONSTRAINT ck_numero_rifa_asignacion
        CHECK (
            (estado = 'DISPONIBLE' AND familia_id IS NULL)
            OR
            (estado IN ('ASIGNADO', 'VENDIDO') AND familia_id IS NOT NULL)
        ),
    CONSTRAINT fk_numero_rifa_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_numero_rifa_rifa_misma_escuela
        FOREIGN KEY (rifa_id, escuela_id)
        REFERENCES gestion_patin.rifa(id, escuela_id),
    CONSTRAINT fk_numero_rifa_familia_misma_escuela
        FOREIGN KEY (familia_id, escuela_id)
        REFERENCES gestion_patin.familia(id, escuela_id),
    CONSTRAINT fk_numero_rifa_actualizado_por_misma_escuela
        FOREIGN KEY (actualizado_por, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id),
    CONSTRAINT uq_numero_por_rifa UNIQUE (rifa_id, numero)
);

CREATE INDEX ix_numero_rifa_familia
    ON gestion_patin.numero_rifa(rifa_id, familia_id, estado);

-- =========================================================
-- 8. AUDITORÍA
-- =========================================================

CREATE TABLE gestion_patin.auditoria (
    id              bigserial PRIMARY KEY,
    escuela_id      uuid NOT NULL,
    usuario_id      uuid,
    accion          varchar(80) NOT NULL,
    recurso_tipo    varchar(80) NOT NULL,
    recurso_id      uuid,
    detalle         jsonb NOT NULL DEFAULT '{}'::jsonb,
    ip              inet,
    user_agent      text,
    creado_en       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_auditoria_escuela
        FOREIGN KEY (escuela_id) REFERENCES gestion_patin.escuela(id),
    CONSTRAINT fk_auditoria_usuario_misma_escuela
        FOREIGN KEY (usuario_id, escuela_id)
        REFERENCES gestion_patin.usuario(id, escuela_id)
);

CREATE INDEX ix_auditoria_escuela_fecha
    ON gestion_patin.auditoria(escuela_id, creado_en DESC);

CREATE INDEX ix_auditoria_recurso
    ON gestion_patin.auditoria(escuela_id, recurso_tipo, recurso_id, creado_en DESC);

-- =========================================================
-- 9. TRIGGER GENERAL DE actualizado_en
-- =========================================================

CREATE OR REPLACE FUNCTION gestion_patin.actualizar_timestamp_modificacion()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.actualizado_en = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_escuela_actualizado
BEFORE UPDATE ON gestion_patin.escuela
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_familia_actualizado
BEFORE UPDATE ON gestion_patin.familia
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_usuario_actualizado
BEFORE UPDATE ON gestion_patin.usuario
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_tutor_actualizado
BEFORE UPDATE ON gestion_patin.tutor
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_deportista_actualizado
BEFORE UPDATE ON gestion_patin.deportista
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_temporada_actualizado
BEFORE UPDATE ON gestion_patin.temporada
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_deportista_temporada_actualizado
BEFORE UPDATE ON gestion_patin.deportista_temporada
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_documento_actualizado
BEFORE UPDATE ON gestion_patin.documento
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_apto_actualizado
BEFORE UPDATE ON gestion_patin.apto_fisico
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_empadronamiento_actualizado
BEFORE UPDATE ON gestion_patin.empadronamiento
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_licencia_actualizado
BEFORE UPDATE ON gestion_patin.licencia
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_competencia_actualizado
BEFORE UPDATE ON gestion_patin.competencia
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_inscripcion_actualizado
BEFORE UPDATE ON gestion_patin.inscripcion_competencia
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_plantilla_lbf_actualizado
BEFORE UPDATE ON gestion_patin.plantilla_lbf
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_rifa_actualizado
BEFORE UPDATE ON gestion_patin.rifa
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

CREATE TRIGGER trg_numero_rifa_actualizado
BEFORE UPDATE ON gestion_patin.numero_rifa
FOR EACH ROW EXECUTE FUNCTION gestion_patin.actualizar_timestamp_modificacion();

-- =========================================================
-- 10. DATOS INICIALES
-- =========================================================

INSERT INTO gestion_patin.escuela (
    nombre,
    nombre_federativo,
    slug,
    email_contacto,
    max_administradores,
    limite_archivo_mb
)
VALUES (
    'Banfield Patín Carrera',
    'Club Social y Deportivo Banfield San Pedro',
    'banfield-patin-san-pedro',
    'patincarrerabanfieldsanpedro@gmail.com',
    2,
    15
)
ON CONFLICT DO NOTHING;

-- No se insertan usuarios ADMIN aquí: las credenciales deben crearse mediante
-- un bootstrap seguro del backend y nunca versionarse en una migración.
--
-- Los buckets privados de Supabase Storage tampoco se crean en Flyway para
-- mantener esta migración compatible con PostgreSQL local/test.
