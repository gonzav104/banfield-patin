package com.banfieldpatin.backend.familias.invitaciones.dto;

import java.time.Instant;

/** Contexto minimo para mostrar el formulario de registro: sin ids internos (ni familia ni invitacion). */
public record InvitacionPublicaRespuesta(boolean valida, String escuelaNombre, String emailSugerido, Instant expiraEn) {
}
