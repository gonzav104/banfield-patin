// Datos que el formulario pide y que se calculan a partir de otros (no se guardan).
export function edad(fechaNacimiento, hoy) {
  const [a, m, d] = fechaNacimiento.split('-').map(Number); const [A, M, D] = hoy.split('-').map(Number);
  return A - a - ((M < m || (M === m && D < d)) ? 1 : 0);
}
// El padrón define "menor" hasta los 17 años y exige WADA a "mayores de 16 años".
export const esMenor = (fechaNacimiento, hoy) => edad(fechaNacimiento, hoy) <= 17;
export const requiereWada = (fechaNacimiento, hoy) => edad(fechaNacimiento, hoy) > 16;
