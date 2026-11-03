// Datos 100 % ficticios (DNI 9xxxxxxx no se emiten; correos en .invalid). Nunca usar datos reales de menores en el spike.
import { esMenor } from './derivados.mjs';

export const HOY = '2026-11-02';
const SI = 'SI'; const NO = 'NO';

const base = { 'escuela.club': 'BANFIELD_SAN_PEDRO', 'federado.funcion': 'ATLETA', 'federado.deporte': 'VELOCIDAD', 'deportista.nacionalidad': 'Argentina' };

export const PERFILES = {
  // 12 años: rama de menores completa (padre y madre), con certificado médico y sin WADA.
  menor: { ...base, 'licencia.tipo': 'REGIONAL', 'deportista.apellidos': 'Prueba Menor', 'deportista.nombres': 'Ana Ficticia', 'deportista.dni': '90000001', 'deportista.cuil': '27-90000001-4',
    'deportista.fechaNacimiento': '2014-05-20', 'deportista.domicilio': 'Calle Ficticia 123', 'deportista.otrosDatosDomicilio': 'Piso 2, depto B', 'deportista.localidad': 'Localidad Ficticia', 'deportista.partido': 'Partido Ficticio',
    'deportista.codigoPostal': '9999', 'deportista.email': 'prueba.menor@example.invalid', 'deportista.telefono': '(11) 5555-0001', 'apto.tiene': SI, 'apto.fechaEmision': '2026-03-10', 'apto.medicoNombre': 'Medica Ficticia, Maria', 'apto.medicoMatricula': 'MP 100001',
    'wada.tiene': NO, 'tutor.madre.nombreCompleto': 'Madre Ficticia, Laura', 'tutor.madre.dni': '90000011', 'tutor.padre.nombreCompleto': 'Padre Ficticio, Carlos', 'tutor.padre.dni': '90000012', 'deportista.empadronado': SI },
  // 26 años, nacido un 29 de febrero: rama de mayores, WADA con fecha y sin sección de padres.
  mayor: { ...base, 'licencia.tipo': 'FEDERATIVA', 'deportista.apellidos': 'Prueba Mayor', 'deportista.nombres': 'Bruno Ficticio', 'deportista.dni': '90000002', 'deportista.cuil': '20-90000002-9',
    'deportista.fechaNacimiento': '2000-02-29', 'deportista.domicilio': 'Avenida Inventada 4567', 'deportista.otrosDatosDomicilio': '', 'deportista.localidad': 'Localidad Ficticia', 'deportista.partido': 'Partido Ficticio',
    'deportista.codigoPostal': '9998', 'deportista.email': 'prueba.mayor@example.invalid', 'deportista.telefono': '1155550002', 'apto.tiene': SI, 'apto.fechaEmision': '2026-01-15', 'apto.medicoNombre': 'Medico Ficticio, Pedro', 'apto.medicoMatricula': '100002',
    'wada.tiene': SI, 'wada.fechaEmision': '2026-02-01', 'deportista.empadronado': SI },
  // 17 años: sin certificado médico (salta a WADA), con WADA, menor (datos de padres) y caracteres especiales; en el apto, no empadronado (el formulario termina).
  condicional: { ...base, 'licencia.tipo': 'SOLO_SEGURO', 'deportista.apellidos': "Núñez-Prueba D'Ficticio", 'deportista.nombres': 'Ñandú José', 'deportista.dni': '90000003', 'deportista.cuil': '23-90000003-1',
    'deportista.fechaNacimiento': '2009-10-01', 'deportista.domicilio': 'Calle & Inventada #7, 1° "A"', 'deportista.otrosDatosDomicilio': 'Barrio Ñuñoa; km 5,5 + fondo', 'deportista.localidad': 'San Pedro', 'deportista.partido': 'Partido Ficticio',
    'deportista.codigoPostal': 'B2930', 'deportista.email': 'ñandu+prueba@example.invalid', 'deportista.telefono': '+54 9 11 5555-0003', 'apto.tiene': NO, 'wada.tiene': SI, 'wada.fechaEmision': '2026-02-01',
    'tutor.madre.nombreCompleto': 'Madre Ficticia, Elena', 'tutor.madre.dni': '90000031', 'tutor.padre.nombreCompleto': 'Padre Ficticio, Raúl', 'tutor.padre.dni': '90000032', 'deportista.empadronado': NO },
};

// Agrega los datos derivados que el formulario pide y que no se guardan.
export function valoresDe(nombre, hoy = HOY) {
  const v = { ...PERFILES[nombre] };
  v['deportista.esMenor'] = esMenor(v['deportista.fechaNacimiento'], hoy) ? SI : NO;
  return v;
}
