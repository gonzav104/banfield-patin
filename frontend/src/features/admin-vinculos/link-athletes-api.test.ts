import { beforeEach, describe, expect, it } from 'vitest';
import { apiWrite } from '@/lib/http';
import type { AthleteSummary } from '@/features/admin-deportistas/schemas';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend } from '@/test/render';
import { getAthleteLinks, linkFamilyAthletes, prepareFamilyLinks } from './api';
import { rawLink } from './links-fixtures';

let backend: FakeBackend;
beforeEach(() => { backend = createBackend(); backend.signIn('marcos@example.com'); });
const posts = () => backend.requests.filter((r) => r.method === 'post' && /^\/admin\/familias\/[^/]+\/deportistas$/.test(r.url));
function seed(count = 2) {
  const family = backend.addFamily('Familia Lote');
  const athletes = Array.from({ length: count }, (_, i) => backend.addAdminAthlete({ nombre: `Atleta ${i}`, apellido: 'Prueba', dni: String(35000000 + i) }));
  const summaries: AthleteSummary[] = athletes.map(({ id, nombre, apellido, activo }) => ({ id, nombre, apellido, activo, dniEnmascarado: '•••••123' }));
  return { family, athletes, summaries };
}

describe('family link batch contract', () => {
  it('prepares one revision per athlete without requesting full athlete detail', async () => {
    const { family, summaries } = seed();
    const snapshot = await prepareFamilyLinks(family.id, summaries);
    expect(snapshot.entries.map((e) => [e.athlete.id, e.versionEsperada, e.relation])).toEqual(summaries.map((a) => [a.id, '0', null]));
    expect(backend.requests.filter((r) => r.url.startsWith('/admin/')).map((r) => r.url)).toEqual([
      `/admin/familias/${family.id}`, ...summaries.map((a) => `/admin/deportistas/${a.id}/vinculos`),
    ]);
    expect(JSON.stringify(snapshot)).not.toContain('35000000');
  });

  it('sends only the exact per-athlete body with fresh CSRF and receives all three outcomes', async () => {
    const { family, athletes, summaries } = seed(3);
    const revoked = backend.addAdminLink(family.id, athletes[1]!.id, { estado: 'REVOCADO' });
    const active = backend.addAdminLink(family.id, athletes[2]!.id, { estado: 'ACTIVO', esPrincipal: true });
    const snapshot = await prepareFamilyLinks(family.id, summaries);
    const result = await linkFamilyAthletes(snapshot);
    expect(posts()).toHaveLength(1);
    expect(posts()[0]).toMatchObject({ body: { deportistas: summaries.map((a) => ({ deportistaId: a.id, versionEsperada: '0' })) } });
    expect(posts()[0]!.csrfHeader).toBeTruthy();
    expect(result.resultados.map((r) => r.resultado)).toEqual(['CREADO', 'REACTIVADO', 'SIN_CAMBIOS']);
    expect(result.resultados[1]!.vinculo.vinculoId).toBe(revoked.id);
    expect(result.resultados[2]!.vinculo.vinculoId).toBe(active.id);
    expect(result.resultados.map((r) => r.vinculo.versionVinculos)).toEqual(['1', '1', '0']);
  });

  it.each([0, 51])('rejects a selection of %i before any reads or writes', async (count) => {
    const { family, summaries } = seed(count);
    await expect(prepareFamilyLinks(family.id, summaries)).rejects.toMatchObject({ kind: 'validation' });
    expect(backend.requests).toHaveLength(0);
  });

  it('accepts the maximum of 50 in one atomic request', async () => {
    const { family, summaries } = seed(50);
    expect((await linkFamilyAthletes(await prepareFamilyLinks(family.id, summaries))).resultados).toHaveLength(50);
    expect(posts()).toHaveLength(1);
  });

  it('rejects duplicate athletes instead of silently changing the prepared order', async () => {
    const { family, summaries } = seed(1);
    await expect(prepareFamilyLinks(family.id, [summaries[0]!, summaries[0]!])).rejects.toMatchObject({ kind: 'validation' });
    expect(posts()).toHaveLength(0);
  });

  it('rejects inactive families, inactive athletes and unsupported historical states', async () => {
    const { family, summaries, athletes } = seed(1);
    family.activa = false;
    await expect(prepareFamilyLinks(family.id, summaries)).rejects.toMatchObject({ code: 'FAMILIA_INACTIVA' });
    family.activa = true;
    await expect(prepareFamilyLinks(family.id, [{ ...summaries[0]!, activo: false }])).rejects.toMatchObject({ code: 'DEPORTISTA_INACTIVO' });
    backend.addAdminLink(family.id, athletes[0]!.id, { estado: 'PENDIENTE' });
    await expect(prepareFamilyLinks(family.id, summaries)).rejects.toMatchObject({ code: 'VINCULO_NO_DISPONIBLE' });
    expect(posts()).toHaveLength(0);
  });

  it('does not partially create links when any observed revision is stale', async () => {
    const { family, summaries } = seed();
    const snapshot = await prepareFamilyLinks(family.id, summaries);
    snapshot.entries[1]!.versionEsperada = '999';
    const before = await Promise.all(summaries.map((a) => getAthleteLinks(a.id)));
    await expect(linkFamilyAthletes(snapshot)).rejects.toMatchObject({ code: 'EDICION_DESACTUALIZADA' });
    expect(await Promise.all(summaries.map((a) => getAthleteLinks(a.id)))).toEqual(before);
    expect(backend.adminLinkCount()).toBe(0);
  });

  it('preserves a decimal revision larger than Number.MAX_SAFE_INTEGER', async () => {
    const { family, summaries } = seed(1);
    const a = summaries[0]!;
    const revision = '9007199254740993';
    backend.override('get', `/admin/deportistas/${a.id}/vinculos`, { status: 200, body: { deportistaId: a.id, versionVinculos: revision, vinculos: [] } });
    const snapshot = await prepareFamilyLinks(family.id, summaries);
    backend.override('post', `/admin/familias/${family.id}/deportistas`, { status: 200, body: { resultados: [{
      vinculo: rawLink({ vinculoId: 'new-link', familiaId: family.id, deportistaId: a.id, estado: 'ACTIVO', versionVinculos: '9007199254740994' }), resultado: 'CREADO',
    }] } });
    await linkFamilyAthletes(snapshot);
    expect(posts()[0]!.body).toEqual({ deportistas: [{ deportistaId: a.id, versionEsperada: revision }] });
  });

  it.each([
    'missing', 'extra', 'duplicate-athlete', 'wrong-family', 'wrong-athlete', 'reordered', 'unknown-result',
    'wrong-state', 'inactive-athlete', 'number-revision', 'overflow-revision', 'unchanged-revision', 'wrong-outcome', 'duplicate-link',
  ])('rejects malformed/misdirected success before any success is exposed: %s', async (change) => {
    const { family, summaries } = seed();
    const snapshot = await prepareFamilyLinks(family.id, summaries);
    const results = summaries.map((a, i) => ({
      vinculo: rawLink({ vinculoId: `new-${i}`, familiaId: family.id, deportistaId: a.id, estado: 'ACTIVO', versionVinculos: '1' }),
      resultado: 'CREADO',
    }));
    if (change === 'missing') results.pop();
    if (change === 'extra') results.push({ ...results[0]! });
    if (change === 'duplicate-athlete') results[1]!.vinculo.deportistaId = summaries[0]!.id;
    if (change === 'wrong-family') results[0]!.vinculo.familiaId = 'other-family';
    if (change === 'wrong-athlete') results[0]!.vinculo.deportistaId = 'other-athlete';
    if (change === 'reordered') results.reverse();
    if (change === 'unknown-result') results[0]!.resultado = 'UNKNOWN';
    if (change === 'wrong-state') results[0]!.vinculo.estado = 'REVOCADO';
    if (change === 'inactive-athlete') results[0]!.vinculo.deportistaActivo = false;
    if (change === 'number-revision') Object.assign(results[0]!.vinculo, { versionVinculos: 1 });
    if (change === 'overflow-revision') results[0]!.vinculo.versionVinculos = '9223372036854775808';
    if (change === 'unchanged-revision') results[0]!.vinculo.versionVinculos = '0';
    if (change === 'wrong-outcome') results[0]!.resultado = 'SIN_CAMBIOS';
    if (change === 'duplicate-link') results[1]!.vinculo.vinculoId = results[0]!.vinculo.vinculoId;
    backend.override('post', `/admin/familias/${family.id}/deportistas`, { status: 200, body: { resultados: results } });
    await expect(linkFamilyAthletes(snapshot)).rejects.toMatchObject({ kind: 'server' });
    expect(posts()).toHaveLength(1);
  });

  it('stops preparation if identity changes while a read is pending', async () => {
    const { family, summaries } = seed();
    const held = backend.hold('get', `/admin/deportistas/${summaries[0]!.id}/vinculos`);
    let current = true;
    const pending = prepareFamilyLinks(family.id, summaries, () => current);
    await new Promise((resolve) => setTimeout(resolve, 0));
    current = false; held.release();
    await expect(pending).rejects.toMatchObject({ code: 'IDENTIDAD_CAMBIADA' });
    expect(backend.requests.some((r) => r.url.includes(summaries[1]!.id))).toBe(false);
  });

  it('does not dispatch POST under a different identity after waiting for fresh CSRF', async () => {
    const { family, summaries } = seed(1);
    const snapshot = await prepareFamilyLinks(family.id, summaries);
    const held = backend.hold('get', '/auth/csrf');
    let current = true;
    const pending = linkFamilyAthletes(snapshot, () => current);
    await new Promise((resolve) => setTimeout(resolve, 0));
    current = false; held.release();
    await expect(pending).rejects.toMatchObject({ code: 'IDENTIDAD_CAMBIADA' });
    expect(posts()).toHaveLength(0);
  });

  it('keeps the existing default HTTP write behavior without an opt-in guard', async () => {
    await apiWrite('post', '/admin/familias', { nombreReferencia: 'Familia HTTP existente' });
    const request = backend.requests.find((r) => r.method === 'post');
    expect(request).toMatchObject({ url: '/admin/familias', body: { nombreReferencia: 'Familia HTTP existente' } });
    expect(request?.csrfHeader).toBeTruthy();
  });
});
