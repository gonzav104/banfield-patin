import { afterEach, describe, expect, it } from 'vitest';
import { dbQuery } from './db';

/**
 * The refusals of the SQL helper that need no Docker: every one of them happens BEFORE any container is touched, so none of
 * these tests can reach a database. (That a container without the disposable label is refused is checked by the E2E run itself,
 * which only works because frontend-e2e.sh labels its container.)
 */
const saved = { container: process.env.E2E_DB_CONTAINER, name: process.env.E2E_DB_NAME };
afterEach(() => {
  if (saved.container === undefined) delete process.env.E2E_DB_CONTAINER;
  else process.env.E2E_DB_CONTAINER = saved.container;
  if (saved.name === undefined) delete process.env.E2E_DB_NAME;
  else process.env.E2E_DB_NAME = saved.name;
});

describe('dbQuery refuses anything that is not the disposable E2E database', () => {
  it('without a configured container', () => {
    delete process.env.E2E_DB_CONTAINER;
    expect(() => dbQuery('select 1')).toThrow(/E2E SQL refused: E2E_DB_CONTAINER is not a container created by frontend-e2e\.sh/);
  });

  it.each(['postgres', 'supabase_db_other_project', 'bp-e2e-postgres', 'bp-e2e-postgres-x; rm -rf /', 'bp-e2e-postgres-x y', '../bp-e2e-postgres-1'])('for the container name %j', (name) => {
    process.env.E2E_DB_CONTAINER = name;
    process.env.E2E_DB_NAME = 'bp_e2e';
    expect(() => dbQuery('select 1')).toThrow(/E2E SQL refused: E2E_DB_CONTAINER/);
  });

  it.each([undefined, '', 'postgres', 'bp_e2e_other', 'bp_e2e; drop database x'])('for the database name %j even if the container looks right', (name) => {
    process.env.E2E_DB_CONTAINER = 'bp-e2e-postgres-local-123';
    if (name === undefined) delete process.env.E2E_DB_NAME;
    else process.env.E2E_DB_NAME = name;
    expect(() => dbQuery('select 1')).toThrow(/E2E SQL refused: E2E_DB_NAME must be exactly "bp_e2e"/);
  });

  it('for a variable name that is not a plain identifier, before touching anything', () => {
    process.env.E2E_DB_CONTAINER = 'bp-e2e-postgres-local-123';
    process.env.E2E_DB_NAME = 'bp_e2e';
    expect(() => dbQuery('select 1', { 'x; drop table y': '1' })).toThrow(/invalid variable name/);
  });

  it('for a well-formed container that does not exist or cannot be inspected (no label, no SQL)', () => {
    process.env.E2E_DB_CONTAINER = 'bp-e2e-postgres-does-not-exist-0000';
    process.env.E2E_DB_NAME = 'bp_e2e';
    expect(() => dbQuery('select 1')).toThrow(/E2E SQL refused: the container could not be inspected/);
  });
});
