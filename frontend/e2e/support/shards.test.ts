import { describe, expect, it } from 'vitest';
import { AUTH_SPEC, checkCoverage, loadManifest, needsEnrolment, patterns, specFiles, validateManifest, type Manifest } from './shards';

/**
 * El reparto de los E2E reales entre runners (e2e/shards.json) protege dos cosas: que ningun escenario se omita o se ejecute dos
 * veces, y que el spec que enrola al administrador vaya primero en su shard. Estas pruebas no necesitan Docker ni navegador.
 */
const FILES = ['auth.real.spec.ts', 'b.real.spec.ts', 'c.real.spec.ts', 'd.real.spec.ts'];
const valid: Manifest = { shards: { '1': ['auth.real.spec.ts', 'b.real.spec.ts'], '2': ['c.real.spec.ts', 'd.real.spec.ts'] } };

describe('validateManifest', () => {
  it('accepts a partition where every spec is in exactly one shard and auth goes first', () => {
    expect(validateManifest(valid, FILES)).toEqual([]);
  });

  it('rejects a spec that is in no shard (it would never run)', () => {
    const problems = validateManifest({ shards: { '1': ['auth.real.spec.ts', 'b.real.spec.ts'], '2': ['c.real.spec.ts'] } }, FILES);
    expect(problems.join('\n')).toContain('d.real.spec.ts no esta en ningun shard');
  });

  it('rejects a spec that is in two shards (it would run twice)', () => {
    const problems = validateManifest({ shards: { '1': ['auth.real.spec.ts', 'b.real.spec.ts'], '2': ['b.real.spec.ts', 'c.real.spec.ts', 'd.real.spec.ts'] } }, FILES);
    expect(problems.join('\n')).toContain('b.real.spec.ts esta repetido');
  });

  it('rejects an entry that is not a spec file (a typo would silently skip the real one)', () => {
    const problems = validateManifest({ shards: { '1': ['auth.real.spec.ts', 'b.real.spec.ts'], '2': ['c.real.spec.ts', 'd.real.spec.ts', 'typo.real.spec.ts'] } }, FILES);
    expect(problems.join('\n')).toContain('typo.real.spec.ts figura en el reparto pero no existe');
  });

  it('rejects empty shards, a single shard and shards that are not numbered 1..N', () => {
    expect(validateManifest({ shards: { '1': FILES } }, FILES).join('\n')).toContain('al menos dos shards');
    expect(validateManifest({ shards: { '1': FILES, '2': [] } }, FILES).join('\n')).toContain('el shard 2 no tiene archivos');
    expect(validateManifest({ shards: { a: ['auth.real.spec.ts', 'b.real.spec.ts'], b: ['c.real.spec.ts', 'd.real.spec.ts'] } }, FILES).join('\n')).toContain('1..N');
  });

  it('requires the enrolling spec to be in exactly one shard and to run before any other spec of it', () => {
    expect(validateManifest({ shards: { '1': ['b.real.spec.ts'], '2': ['c.real.spec.ts', 'd.real.spec.ts'] } }, ['b.real.spec.ts', 'c.real.spec.ts', 'd.real.spec.ts']).join('\n')).toContain(
      `${AUTH_SPEC} debe estar en exactamente un shard`,
    );
    // «a-first» sorts before auth: it would run before the administrator exists.
    const files = ['a-first.real.spec.ts', ...FILES];
    const problems = validateManifest({ shards: { '1': ['a-first.real.spec.ts', 'auth.real.spec.ts', 'b.real.spec.ts'], '2': ['c.real.spec.ts', 'd.real.spec.ts'] } }, files);
    expect(problems.join('\n')).toContain('se ejecutaria antes que auth.real.spec.ts');
  });
});

describe('patterns and enrolment', () => {
  it('builds anchored, escaped filters (a name that is a prefix of another never matches both)', () => {
    expect(patterns(valid, '2')).toEqual(['e2e/c\\.real\\.spec\\.ts$', 'e2e/d\\.real\\.spec\\.ts$']);
    const regex = new RegExp(patterns({ shards: { '1': ['portal.real.spec.ts'] } }, '1')[0]!);
    expect(regex.test('/repo/frontend/e2e/portal.real.spec.ts')).toBe(true);
    expect(regex.test('/repo/frontend/e2e/portal-ficha.real.spec.ts')).toBe(false);
    expect(regex.test('/repo/frontend/e2e/xportalareal.spec.ts')).toBe(false);
  });

  it('rejects an unknown shard', () => {
    expect(() => patterns(valid, '9')).toThrow(/No existe el shard/);
    expect(() => needsEnrolment(valid, '9')).toThrow(/No existe el shard/);
  });

  it('enrols the administrator through the API only in the shards that do not run auth.real.spec.ts', () => {
    expect(needsEnrolment(valid, '1')).toBe(false);
    expect(needsEnrolment(valid, '2')).toBe(true);
  });
});

describe('checkCoverage', () => {
  const suite = ['auth.real.spec.ts:1:enrols:chromium', 'auth.real.spec.ts:2:second:chromium', 'b.real.spec.ts:1:one:chromium', 'c.real.spec.ts:1:two:chromium', 'd.real.spec.ts:1:three:chromium'];
  /** Like Playwright: the filters are regular expressions tested against the FILE of each scenario. */
  const byFile = (scenario: string) => `/repo/frontend/e2e/${scenario.split(':')[0]}`;
  const lister = (all: string[]) => (filters: string[] = []) =>
    filters.length === 0 ? all : all.filter((scenario) => filters.some((filter) => new RegExp(filter).test(byFile(scenario))));

  it('counts every scenario exactly once across the shards', () => {
    const result = checkCoverage(valid, lister(suite));
    expect(result.problems).toEqual([]);
    expect(result.total).toBe(5);
    expect(result.perShard).toEqual({ '1': 3, '2': 2 });
  });

  it('reports a scenario that no shard would run and a shard that would run nothing', () => {
    const result = checkCoverage({ shards: { '1': ['auth.real.spec.ts', 'b.real.spec.ts'], '2': ['c.real.spec.ts'] } }, lister(suite));
    expect(result.problems.join('\n')).toContain('escenario fuera de todos los shards: d.real.spec.ts:1:three:chromium');
    const empty = checkCoverage({ shards: { '1': ['auth.real.spec.ts', 'b.real.spec.ts'], '2': ['zzz.real.spec.ts'] } }, lister(suite));
    expect(empty.problems.join('\n')).toContain('el shard 2 no ejecutaria ningun escenario');
  });

  it('reports a scenario that two shards would run', () => {
    const overlapping = { shards: { '1': ['auth.real.spec.ts', 'b.real.spec.ts', 'c.real.spec.ts'], '2': ['c.real.spec.ts', 'd.real.spec.ts'] } };
    expect(checkCoverage(overlapping, lister(suite)).problems.join('\n')).toContain('escenario en los shards 1 y 2');
  });

  it('requires the enrolling scenario to be the first one of its shard', () => {
    const reordered = ['b.real.spec.ts:1:one:chromium', ...suite.filter((s) => !s.startsWith('b.'))];
    const result = checkCoverage(valid, lister(reordered));
    expect(result.problems.join('\n')).toContain('el primer escenario del shard 1 no es el de auth.real.spec.ts');
  });
});

describe('the real partition (e2e/shards.json)', () => {
  it('assigns every spec file of the repository to exactly one shard, with the enrolling spec first', () => {
    expect(validateManifest(loadManifest(), specFiles())).toEqual([]);
  });

  it('keeps the shard that runs auth.real.spec.ts free of the API enrolment and enrols the others', () => {
    const manifest = loadManifest();
    const withAuth = Object.keys(manifest.shards).filter((name) => !needsEnrolment(manifest, name));
    expect(withAuth).toHaveLength(1);
    expect(manifest.shards[withAuth[0]!]).toContain(AUTH_SPEC);
  });
});
