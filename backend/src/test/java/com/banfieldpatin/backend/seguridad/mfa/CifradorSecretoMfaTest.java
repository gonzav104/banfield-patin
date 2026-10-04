package com.banfieldpatin.backend.seguridad.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CifradorSecretoMfaTest {

	private static final byte[] CLAVE = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] OTRA_CLAVE = "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ".getBytes(StandardCharsets.US_ASCII);

	private final CifradorSecretoMfa cifrador = CifradorSecretoMfa.conClave(CLAVE);
	private final UUID usuario = UUID.randomUUID();
	private final byte[] secreto = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

	@Test
	void idaYVueltaDevuelveElSecretoOriginal() {
		byte[] almacenado = cifrador.cifrar(secreto, usuario);

		assertThat(cifrador.descifrar(almacenado, usuario)).isEqualTo(secreto);
	}

	@Test
	void laSalidaEsNonceMasTextoCifradoMasEtiquetaYNoContieneElSecretoEnClaro() {
		byte[] almacenado = cifrador.cifrar(secreto, usuario);

		assertThat(almacenado).hasSize(12 + secreto.length + 16);
		assertThat(new String(almacenado, StandardCharsets.ISO_8859_1)).doesNotContain(new String(secreto,
				StandardCharsets.ISO_8859_1));
	}

	@Test
	void cadaCifradoUsaUnNonceDistinto() {
		byte[] a = cifrador.cifrar(secreto, usuario);
		byte[] b = cifrador.cifrar(secreto, usuario);

		assertThat(Arrays.copyOfRange(a, 0, 12)).isNotEqualTo(Arrays.copyOfRange(b, 0, 12));
		assertThat(a).isNotEqualTo(b);
	}

	@Test
	void alterarCualquierByteHaceFallarElDescifrado() {
		byte[] almacenado = cifrador.cifrar(secreto, usuario);
		for (int i = 0; i < almacenado.length; i++) {
			byte[] alterado = almacenado.clone();
			alterado[i] ^= 0x01;
			assertThatThrownBy(() -> cifrador.descifrar(alterado, usuario)).as("byte %d", i)
					.isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	void unUsuarioDistintoComoAadNoDescifra() {
		byte[] almacenado = cifrador.cifrar(secreto, usuario);

		assertThatThrownBy(() -> cifrador.descifrar(almacenado, UUID.randomUUID()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void otraClaveNoDescifra() {
		byte[] almacenado = cifrador.cifrar(secreto, usuario);

		assertThatThrownBy(() -> CifradorSecretoMfa.conClave(OTRA_CLAVE).descifrar(almacenado, usuario))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void unaSalidaDemasiadoCortaOAusenteFallaSinFiltrarDatos() {
		assertThatThrownBy(() -> cifrador.descifrar(new byte[10], usuario)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cifrador.descifrar(new byte[28], usuario)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cifrador.descifrar(null, usuario)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void losMensajesDeErrorNoContienenElSecretoNiLaClave() {
		byte[] almacenado = cifrador.cifrar(secreto, usuario);
		almacenado[almacenado.length - 1] ^= 0x01;

		assertThatThrownBy(() -> cifrador.descifrar(almacenado, usuario))
				.hasMessageNotContaining("12345678901234567890")
				.hasMessageNotContaining(new String(CLAVE, StandardCharsets.US_ASCII))
				.hasNoCause();
	}

	@Test
	void laClaveDebeTener32Bytes() {
		assertThatThrownBy(() -> CifradorSecretoMfa.conClave(new byte[16])).isInstanceOf(IllegalArgumentException.class);
	}
}
