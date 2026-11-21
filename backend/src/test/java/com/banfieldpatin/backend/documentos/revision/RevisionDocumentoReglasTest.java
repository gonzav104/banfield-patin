package com.banfieldpatin.backend.documentos.revision;

import static com.banfieldpatin.backend.documentos.EstadoDocumento.APROBADO;
import static com.banfieldpatin.backend.documentos.EstadoDocumento.CARGADO;
import static com.banfieldpatin.backend.documentos.EstadoDocumento.OBSERVADO;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.banfieldpatin.backend.documentos.EstadoDocumento;

class RevisionDocumentoReglasTest {
    @Test
    void soloAdmiteLasTresTransicionesDelCircuito() {
        record Transicion(EstadoDocumento origen, EstadoDocumento destino) {}
        var valores = Arrays.asList(EstadoDocumento.values());
        var permitidas = valores.stream()
                .flatMap(origen -> valores.stream().filter(destino -> RevisionDocumentoService.permitida(origen, destino))
                        .map(destino -> new Transicion(origen, destino)))
                .toList();
        assertThat(permitidas).containsExactlyInAnyOrderElementsOf(List.of(
                new Transicion(CARGADO, APROBADO), new Transicion(CARGADO, OBSERVADO),
                new Transicion(APROBADO, OBSERVADO)));
    }
}
