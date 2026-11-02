// El ejecutor es un experimento manual; nunca un componente de un flujo de producción.
export function exigirEntornoExperimental(env = process.env) {
  if (env.NODE_ENV !== 'development') throw new Error('El ejecutor experimental no está disponible fuera de desarrollo ni en producción');
  if (env.FEDERACION_SPIKE_EXPERIMENTAL !== '1') throw new Error('El ejecutor experimental requiere autorización local explícita');
}
