package com.banfieldpatin.backend.documentos.carga;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContratoCargaTest {
  @Test
  void requierePreparadorConValidacionRealYScannerPrivado() throws Exception {
    assertThat(Class.forName("com.banfieldpatin.backend.documentos.carga.PreparadorDocumento"))
        .isNotNull();
    assertThat(Class.forName("com.banfieldpatin.backend.documentos.carga.ClamAvPrivado"))
        .isNotNull();
  }
}
