import type { ApiError } from '@/lib/api-error';

/** User-facing copy for failures common to every form. Returns null when the caller must decide. */
export function commonErrorMessage(error: ApiError): string | null {
  switch (error.kind) {
    case 'network':
      return 'No pudimos conectar con el servidor. Revisá tu conexión e intentá de nuevo.';
    case 'unavailable':
      return 'El servicio no está disponible por el momento. Intentá de nuevo en unos instantes.';
    case 'csrf':
      return 'No pudimos validar la solicitud. Recargá la página e intentá de nuevo.';
    case 'server':
      return 'Ocurrió un problema en el servidor. Intentá de nuevo en unos minutos.';
    default:
      return null;
  }
}

export function loginErrorMessage(error: ApiError): string {
  if (error.kind === 'unauthenticated' && error.code === 'CREDENCIALES_INVALIDAS') {
    return 'El correo o la contraseña no son correctos. Si fallaste varias veces, esperá unos minutos antes de reintentar.';
  }
  if (error.kind === 'validation') return 'Revisá los datos ingresados e intentá de nuevo.';
  return commonErrorMessage(error) ?? 'No pudimos iniciar sesión. Intentá de nuevo.';
}

export const SESSION_EXPIRED_MESSAGE = 'Tu sesión venció. Ingresá de nuevo para continuar.';
