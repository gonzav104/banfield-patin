import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
export const RAIZ = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const leerJson = (...partes) => JSON.parse(fs.readFileSync(path.join(RAIZ, ...partes), 'utf8'));
export const crudo = (form) => leerJson('fixtures', `${form}-2026.esquema.json`);
export const contrato = (form) => leerJson('contratos', `${form}-2026.contrato.json`);
export const perfil = (form) => leerJson('perfiles', `${form}-2026.json`);
export const clonar = (o) => structuredClone(o);
export const item = (raw, titulo) => raw.items.find((i) => i[1] === titulo);
// La pregunta (definición) de un ítem: [entry, opciones, obligatoria, ?, validación, ...]
export const def = (raw, titulo) => item(raw, titulo)[4][0];
