package com.banfieldpatin.backend.documentos.carga;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;
import com.banfieldpatin.backend.documentos.almacen.AlmacenDocumentos;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.CannotCreateTransactionException;

class ServicioCargaFalloTest {
  @TempDir Path dir;

  @Test
  void fallaDeTransaccionAntesDeProcesarEs503Saneado() {
    var operaciones = mock(OperacionesCargaJdbc.class);
    var almacen = mock(AlmacenDocumentos.class);
    doThrow(new CannotCreateTransactionException("SENSIBLE-SINTETICO-PROVEEDOR"))
        .when(operaciones)
        .preautorizar(any(), any());
    var servicio =
        new CargaDocumentalService(
            operaciones, PreparadorDocumentoTest.propiedades(dir, 15000000), almacen);
    assertThatThrownBy(() -> servicio.preautorizar(null, UUID.randomUUID()))
        .isInstanceOfSatisfying(
            ExcepcionNegocio.class,
            e -> {
              assertThat(e.getEstado().value()).isEqualTo(503);
              assertThat(e.getCause()).isNull();
              assertThat(e.getMessage()).doesNotContain("SENSIBLE-SINTETICO-PROVEEDOR");
            });
    verifyNoInteractions(almacen);
  }
}
