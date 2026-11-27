package com.banfieldpatin.backend.familias.perfil.dto;

import java.util.List;

/**
 * Estado del autoservicio de perfil de la cuenta: si está habilitado en este entorno, su perfil (o null) y, solo si todavía no
 * tiene perfil, las personas de SU familia sin cuenta que podría reclamar.
 */
public record PerfilRespuesta(boolean habilitado, PerfilDatos perfil, List<PersonaDeLaFamilia> personasSinCuenta) {
}
