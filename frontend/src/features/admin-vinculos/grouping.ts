import type { LinkRow } from './schemas';

/** Only an ACTIVE link gives access to the family (rule F3): the other three states are history. */
export interface LinkGroups {
  active: LinkRow[];
  previous: LinkRow[];
}

/** Splits the links in the two groups the screens draw, keeping the order of the server inside each one. Nothing is lost or repeated. */
export function groupLinks(rows: LinkRow[]): LinkGroups {
  return {
    active: rows.filter((row) => row.estado === 'ACTIVO'),
    previous: rows.filter((row) => row.estado !== 'ACTIVO'),
  };
}

/** «Principal» only means something in an ACTIVE link: a flag carried by any other state is never drawn. */
export const isPrincipalLink = (row: LinkRow): boolean => row.estado === 'ACTIVO' && row.esPrincipal;
