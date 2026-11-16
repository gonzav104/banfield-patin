import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Reparto de los E2E reales entre runners de CI (ver e2e/shards.json y .github/workflows/frontend.yml). Es un reparto EXPLICITO por
 * archivo y no `playwright --shard`: el administrador se enrola en el primer escenario de auth.real.spec.ts y todos los demas
 * dependen de ese enrolamiento, asi que ese spec tiene que ir primero en su shard (los demas shards lo enrolan antes por API), y el
 * reparto se balancea por el tiempo medido y no por la cantidad de escenarios.
 */
const E2E_DIR = join(dirname(fileURLToPath(import.meta.url)), '..');
const FRONTEND_DIR = join(E2E_DIR, '..');

/** El spec cuyo primer escenario enrola al administrador por la interfaz. */
export const AUTH_SPEC = 'auth.real.spec.ts';

export interface Manifest {
  shards: Record<string, string[]>;
}

export function loadManifest(path = join(E2E_DIR, 'shards.json')): Manifest {
  return JSON.parse(readFileSync(path, 'utf8')) as Manifest;
}

/** Los specs que Playwright recoge (testMatch **\/*.spec.ts bajo e2e/), en el orden en que los ejecuta: el de cadenas. */
export function specFiles(dir = E2E_DIR): string[] {
  return readdirSync(dir).filter((name) => name.endsWith('.spec.ts')).sort();
}

/** Devuelve los problemas del reparto (vacio = valido): ni faltan, ni sobran, ni se repiten archivos, y auth va primero en su shard. */
export function validateManifest(manifest: Manifest, files: string[]): string[] {
  const problems: string[] = [];
  const names = Object.keys(manifest.shards ?? {});
  if (names.length < 2) problems.push('el reparto necesita al menos dos shards');
  names.forEach((name, index) => {
    if (name !== String(index + 1)) problems.push(`los shards deben llamarse 1..N en orden (encontrado «${name}»)`);
  });
  const owners = new Map<string, string[]>();
  for (const name of names) {
    const list = manifest.shards[name];
    if (!Array.isArray(list) || list.length === 0) {
      problems.push(`el shard ${name} no tiene archivos`);
      continue;
    }
    for (const file of list) owners.set(file, [...(owners.get(file) ?? []), name]);
  }
  for (const [file, shards] of owners) {
    if (shards.length > 1) problems.push(`${file} esta repetido (shards ${shards.join(', ')}): se ejecutaria dos veces`);
    if (!files.includes(file)) problems.push(`${file} figura en el reparto pero no existe como spec`);
  }
  for (const file of files) {
    if (!owners.has(file)) problems.push(`${file} no esta en ningun shard: nunca se ejecutaria`);
  }
  const withAuth = names.filter((name) => manifest.shards[name]?.includes(AUTH_SPEC));
  if (withAuth.length !== 1) {
    problems.push(`${AUTH_SPEC} debe estar en exactamente un shard (esta en ${withAuth.length})`);
  } else {
    const first = [...manifest.shards[withAuth[0]!]!].sort()[0];
    if (first !== AUTH_SPEC) problems.push(`en el shard ${withAuth[0]} ${first} se ejecutaria antes que ${AUTH_SPEC}, que enrola al administrador`);
  }
  return problems;
}

/** Filtros (expresiones regulares ancladas) con los que Playwright recibe los archivos de un shard. */
export function patterns(manifest: Manifest, shard: string): string[] {
  const list = manifest.shards[shard];
  if (!list) throw new Error(`No existe el shard «${shard}»: los validos son ${Object.keys(manifest.shards).join(', ')}.`);
  return [...list].sort().map((file) => `e2e/${file.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`);
}

/** El shard que no contiene a auth.real.spec.ts tiene que enrolar al administrador por API antes de empezar. */
export function needsEnrolment(manifest: Manifest, shard: string): boolean {
  const list = manifest.shards[shard];
  if (!list) throw new Error(`No existe el shard «${shard}».`);
  return !list.includes(AUTH_SPEC);
}

interface ListedSpec {
  title: string;
  file: string;
  line: number;
  tests?: { projectName?: string }[];
}
interface ListedSuite {
  suites?: ListedSuite[];
  specs?: ListedSpec[];
}

/** Escenarios que Playwright ejecutaria con esos filtros, en el orden de ejecucion: archivo, linea, titulo y proyecto. */
export function listScenarios(filters: string[] = []): string[] {
  const out = execFileSync('npx', ['playwright', 'test', '--list', '--reporter=json', ...filters], {
    cwd: FRONTEND_DIR,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  const found: string[] = [];
  const walk = (suite: ListedSuite) => {
    for (const spec of suite.specs ?? []) {
      for (const test of spec.tests ?? [{}]) found.push(`${spec.file}:${spec.line}:${spec.title}:${test.projectName ?? ''}`);
    }
    for (const child of suite.suites ?? []) walk(child);
  };
  for (const suite of (JSON.parse(out) as { suites: ListedSuite[] }).suites) walk(suite);
  return found;
}

/** Cobertura exacta: la union de los shards son TODOS los escenarios, sin omitir ni duplicar ninguno. Devuelve los problemas. */
export function checkCoverage(manifest: Manifest, list: (filters?: string[]) => string[] = listScenarios): { problems: string[]; total: number; perShard: Record<string, number> } {
  const problems: string[] = [];
  const all = list();
  if (new Set(all).size !== all.length) problems.push('hay escenarios repetidos en la suite completa');
  const seen = new Map<string, string>();
  const perShard: Record<string, number> = {};
  for (const name of Object.keys(manifest.shards)) {
    const scenarios = list(patterns(manifest, name));
    perShard[name] = scenarios.length;
    if (scenarios.length === 0) problems.push(`el shard ${name} no ejecutaria ningun escenario`);
    for (const scenario of scenarios) {
      const other = seen.get(scenario);
      if (other !== undefined) problems.push(`escenario en los shards ${other} y ${name}: ${scenario}`);
      else seen.set(scenario, name);
    }
    if (needsEnrolment(manifest, name) === false && scenarios[0] !== undefined && !scenarios[0].startsWith(`${AUTH_SPEC}:`)) {
      problems.push(`el primer escenario del shard ${name} no es el de ${AUTH_SPEC} (el que enrola al administrador)`);
    }
  }
  for (const scenario of all) if (!seen.has(scenario)) problems.push(`escenario fuera de todos los shards: ${scenario}`);
  for (const scenario of seen.keys()) if (!all.includes(scenario)) problems.push(`escenario de un shard que no esta en la suite: ${scenario}`);
  return { problems, total: all.length, perShard };
}

function fail(lines: string[]): never {
  for (const line of lines) console.error(`[e2e-shards] ${line}`);
  process.exit(1);
}

// CLI: node e2e/support/shards.ts <patterns|needs-enrolment|check> [shard]
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  try {
    const [command, shard] = process.argv.slice(2);
    const manifest = loadManifest();
    const problems = validateManifest(manifest, specFiles());
    if (problems.length > 0) fail(problems);
    if (command === 'patterns' && shard) {
      console.log(patterns(manifest, shard).join('\n'));
    } else if (command === 'needs-enrolment' && shard) {
      console.log(needsEnrolment(manifest, shard) ? 'yes' : 'no');
    } else if (command === 'check') {
      const { problems: coverage, total, perShard } = checkCoverage(manifest);
      if (coverage.length > 0) fail(coverage);
      const sum = Object.values(perShard).reduce((a, b) => a + b, 0);
      console.log(`[e2e-shards] reparto valido: ${total} escenarios = ${Object.entries(perShard).map(([name, count]) => `shard ${name}: ${count}`).join(' + ')} (suma ${sum}), sin omitir ni duplicar ninguno`);
    } else {
      fail(['uso: node e2e/support/shards.ts <patterns|needs-enrolment> <shard> | check']);
    }
  } catch (error) {
    fail([error instanceof Error ? error.message : 'error desconocido']); // un mensaje claro y exit 1, sin volcado de la pila
  }
}
