import type { FakeAdminAthlete, FakeBackend } from '@/test/fakeBackend';

/** Synthetic personal data of the seeded athlete: the screens must never ask for it, keep it or draw it. */
export const DNI = '30111322';
export const CUIL = '20301113227';
/** The revision the fake backend holds for an athlete that was never changed would be '0': tests that look for it in the screen use a distinctive one. */
export const REVISION = 987654321;

export function seedAthlete(backend: FakeBackend, over: Partial<Pick<FakeAdminAthlete, 'activo' | 'nombre' | 'apellido'>> = {}) {
  const athlete = backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: DNI, cuil: CUIL, domicilio: 'Calle Falsa 123', ...over });
  athlete.version = REVISION;
  return athlete;
}

/** A realistic mix: an active principal link, an active non-principal one and a revoked one (which keeps its history). */
export function seedMixedLinks(backend: FakeBackend, athleteOver: Parameters<typeof seedAthlete>[1] = {}) {
  const athlete = seedAthlete(backend, athleteOver);
  const gomez = backend.addFamily('Familia Gómez');
  const perez = backend.addFamily('Familia Pérez');
  const ruiz = backend.addFamily('Familia Ruiz');
  const gomezLink = backend.addAdminLink(gomez.id, athlete.id, { estado: 'ACTIVO', esPrincipal: true });
  const perezLink = backend.addAdminLink(perez.id, athlete.id, { estado: 'ACTIVO', esPrincipal: false });
  const ruizLink = backend.addAdminLink(ruiz.id, athlete.id, { estado: 'REVOCADO' });
  return { athlete, gomez, perez, ruiz, gomezLink, perezLink, ruizLink };
}

/** The shape the backend sends for one link (VinculoRespuesta), to build answers the fake model cannot produce. */
export function rawLink(over: Record<string, unknown> = {}) {
  return {
    vinculoId: 'vin-x',
    familiaId: 'fam-x',
    familiaNombre: 'Familia X',
    deportistaId: 'dep-x',
    deportistaNombre: 'Eva',
    deportistaApellido: 'Gómez',
    deportistaActivo: true,
    estado: 'ACTIVO',
    esPrincipal: false,
    autorizadoEn: '2026-03-01T12:00:00Z',
    versionVinculos: '3',
    ...over,
  };
}
