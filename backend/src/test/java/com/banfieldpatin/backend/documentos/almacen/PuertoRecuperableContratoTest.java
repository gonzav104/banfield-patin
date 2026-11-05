package com.banfieldpatin.backend.documentos.almacen;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PuertoRecuperableContratoTest {
    @Test void preparaDestinoAntesDeTransferir() throws Exception {
        assertThat(AlmacenDocumentos.class.getMethod("prepararDestino").getReturnType().getSimpleName())
                .isEqualTo("DestinoPreparado");
    }
    @Test void noOfreceCompensacionRecuperadaSinAutorizacionDePublicacion() throws Exception {
        var borrados = java.util.Arrays.stream(AlmacenDocumentos.class.getMethods())
                .filter(m -> m.getName().startsWith("eliminar")).toList();
        assertThat(borrados).hasSize(1);
        assertThat(borrados.getFirst().getParameterTypes()).containsExactly(AlmacenDocumentos.Creado.class);
        assertThat(AlmacenDocumentos.class.getMethod("crear", DestinoPreparado.class,
                java.io.InputStream.class, long.class, String.class).getReturnType()).isEqualTo(void.class);
    }

}
