package com.banfieldpatin.backend.documentos.apto;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.banfieldpatin.backend.compartido.error.DetalleError;
import com.banfieldpatin.backend.compartido.error.ExcepcionNegocio;

/**
 * Metadatos del apto físico que declara quien lo carga: fecha de emisión, nombre del médico y matrícula. Son datos de un menor y de
 * un profesional: no se imprimen (ni en {@code toString}), no se auditan y no se registran. El sistema no interpreta ni guarda nada
 * clínico del archivo.
 */
public record MetadatosApto(LocalDate fechaEmision, String medicoNombre, String medicoMatricula) {

    public static final LocalDate EMISION_MINIMA = LocalDate.of(2000, 1, 1);
    public static final int NOMBRE_MAXIMO = 150;
    public static final int MATRICULA_MAXIMA = 50;
    /** Alfanumérica: letras y dígitos, con espacio, punto, guion y barra como separadores (por ejemplo «MP 12.345» o «M.N. 123/4»). */
    private static final Pattern MATRICULA = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} ./-]*$");

    /** Los tres datos tal como llegan, sin validar: todos nulos significa «no se enviaron». */
    public record Solicitado(String fechaEmision, String medicoNombre, String medicoMatricula) {
        public boolean vacio() {
            return fechaEmision == null && medicoNombre == null && medicoMatricula == null;
        }

        @Override
        public String toString() {
            return "AptoSolicitado[***]";
        }
    }

    /**
     * Valida y normaliza (trim). La fecha es ISO {@code aaaa-mm-dd}, no futura en la zona de la escuela y no anterior a 2000. Un error
     * lista solo NOMBRES de campos, nunca los valores enviados.
     */
    public static MetadatosApto validar(Solicitado s, Clock reloj) {
        List<DetalleError> errores = new ArrayList<>();
        LocalDate fecha = null;
        String fechaTexto = s.fechaEmision() == null ? "" : s.fechaEmision().trim();
        if (fechaTexto.isEmpty()) {
            errores.add(new DetalleError("fechaEmision", "La fecha de emisión es obligatoria."));
        } else {
            try {
                fecha = LocalDate.parse(fechaTexto);
                LocalDate hoy = LocalDate.now(reloj.withZone(ReglaVigenciaApto.ZONA));
                if (fecha.isAfter(hoy) || fecha.isBefore(EMISION_MINIMA)) {
                    errores.add(new DetalleError("fechaEmision", "La fecha de emisión no es válida."));
                }
            } catch (DateTimeParseException e) {
                errores.add(new DetalleError("fechaEmision", "La fecha de emisión no es válida."));
            }
        }
        String nombre = s.medicoNombre() == null ? "" : s.medicoNombre().trim();
        if (nombre.isEmpty() || nombre.length() > NOMBRE_MAXIMO || nombre.chars().anyMatch(Character::isISOControl)) {
            errores.add(new DetalleError("medicoNombre", "El nombre del médico es obligatorio y de hasta 150 caracteres."));
        }
        String matricula = s.medicoMatricula() == null ? "" : s.medicoMatricula().trim();
        if (matricula.isEmpty() || matricula.length() > MATRICULA_MAXIMA || !MATRICULA.matcher(matricula).matches()) {
            errores.add(new DetalleError("medicoMatricula", "La matrícula es obligatoria, alfanumérica y de hasta 50 caracteres."));
        }
        if (!errores.isEmpty()) {
            throw new ExcepcionNegocio(HttpStatus.BAD_REQUEST, "VALIDACION", "Hay datos inválidos en la solicitud.", errores);
        }
        return new MetadatosApto(fecha, nombre, matricula);
    }

    @Override
    public String toString() {
        return "MetadatosApto[***]";
    }
}
