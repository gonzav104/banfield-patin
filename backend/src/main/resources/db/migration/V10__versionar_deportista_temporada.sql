-- Revision de escritura de cada fila de datos deportivos por temporada (deportista_temporada). No altera los datos deportivos
-- almacenados, ni las unicidades (uq_deportista_temporada, uq_numero_corredor_por_temporada), ni las claves foraneas. Cada fila
-- lleva su propia revision: editar una temporada no mueve la de otra ni la de la ficha permanente del deportista.
ALTER TABLE gestion_patin.deportista_temporada
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_deportista_temporada_version_no_negativa CHECK (version >= 0);
