package com.banfieldpatin.backend.seguridad;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Sustituto de prueba del {@link VerificadorSesionVigente} para los {@code @WebMvcTest} que importan
 * {@link SeguridadConfig}: esos tests emiten tokens para ids al azar que no existen en ninguna base, asi que la
 * revalidacion central (REQ-XC-09) debe responder {@code true} por defecto. Cada test puede cambiarlo
 * ({@link Verificador#rechazar()}, {@link Verificador#fallarConBaseNoDisponible()}) y lee cuantas veces se consulto.
 * <p>
 * El bean es un singleton del contexto de prueba: quien lo cambie debe llamar a {@link Verificador#reiniciar()} en su
 * {@code @BeforeEach} para no contaminar a otros tests que compartan el contexto.
 */
@TestConfiguration
public class SesionVigenteDePrueba {

	@Bean
	Verificador verificadorSesionVigenteDePrueba() {
		return new Verificador();
	}

	public static class Verificador implements VerificadorSesionVigente {

		private enum Modo { PERMITIR, RECHAZAR, FALLAR }

		private volatile Modo modo = Modo.PERMITIR;
		private final AtomicInteger llamadas = new AtomicInteger();

		@Override
		public boolean vigente(UUID usuarioId, UUID escuelaId, String rol, UUID familiaId) {
			llamadas.incrementAndGet();
			return switch (modo) {
				case PERMITIR -> true;
				case RECHAZAR -> false;
				case FALLAR -> throw new DataAccessResourceFailureException("base de datos no disponible (simulada)");
			};
		}

		public void permitir() {
			modo = Modo.PERMITIR;
		}

		public void rechazar() {
			modo = Modo.RECHAZAR;
		}

		public void fallarConBaseNoDisponible() {
			modo = Modo.FALLAR;
		}

		public int llamadas() {
			return llamadas.get();
		}

		/** Vuelve al estado por defecto: vigente y sin llamadas registradas. */
		public void reiniciar() {
			modo = Modo.PERMITIR;
			llamadas.set(0);
		}
	}
}
