import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { StatusBadge, type BadgeTone } from '@/features/familia/StatusBadge';
import { groupLinks } from './grouping';
import { ACTIVE_GROUP, LINK_STATE_LABEL, PREVIOUS_GROUP } from './messages';
import type { LinkRow, LinkState } from './schemas';

const STATE_TONE: Record<LinkState, BadgeTone> = { ACTIVO: 'positive', REVOCADO: 'neutral', PENDIENTE: 'warning', RECHAZADO: 'warning' };

/** The state of the RELATION, as text: never only colour. Not the state of the athlete in the school, and not the principal flag. */
export function LinkStateBadge({ state }: { state: LinkState }) {
  return <StatusBadge tone={STATE_TONE[state]}>{LINK_STATE_LABEL[state]}</StatusBadge>;
}

/** A marker of the principal condition (the text says WHAT is principal: the family for an athlete, the athlete for a family). */
export function PrincipalBadge({ children }: { children: string }) {
  return <StatusBadge tone="positive">{children}</StatusBadge>;
}

/** A neutral card for «there is nothing here»: one sentence, with no action to create anything. */
export function LinksNotice({ children }: { children: ReactNode }) {
  return <p className="rounded-2xl border border-dashed border-line bg-white p-5 text-sm leading-relaxed text-muted">{children}</p>;
}

/** A failed read with its fixed message and an explicit retry. A refresh that fails never gets here while data is on screen. */
export function LinksReadError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="space-y-4">
      <Alert tone="error">{message}</Alert>
      <Button variant="outline" onClick={onRetry}>
        Reintentar
      </Button>
    </div>
  );
}

/** The link to a family or an athlete: its name, with no other data. */
export function LinkTarget({ to, children }: { to: string; children: ReactNode }) {
  return (
    <Link to={to} className="min-w-0 break-words font-semibold text-ink underline-offset-4 hover:underline focus-visible:underline">
      {children}
    </Link>
  );
}

function Group({ title, level, rows, renderItem }: { title: string; level: 'h2' | 'h3'; rows: LinkRow[]; renderItem: (row: LinkRow) => ReactNode }) {
  const Heading = level;
  return (
    <div className="space-y-2">
      <Heading className="text-base font-bold">{title}</Heading>
      <ul aria-label={title} className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-white">
        {rows.map((row) => (
          <li key={row.vinculoId} className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-4">
            {renderItem(row)}
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * The links in two groups: the active ones (the only ones that give access) and the previous ones (revoked, pending, rejected). A group with
 * no rows is not drawn. Each screen draws its own item: this only decides what goes where.
 */
export function LinkGroups({ rows, level, renderItem }: { rows: LinkRow[]; level: 'h2' | 'h3'; renderItem: (row: LinkRow) => ReactNode }) {
  const { active, previous } = groupLinks(rows);
  return (
    <div className="space-y-5">
      {active.length > 0 ? <Group title={ACTIVE_GROUP} level={level} rows={active} renderItem={renderItem} /> : null}
      {previous.length > 0 ? <Group title={PREVIOUS_GROUP} level={level} rows={previous} renderItem={renderItem} /> : null}
    </div>
  );
}
