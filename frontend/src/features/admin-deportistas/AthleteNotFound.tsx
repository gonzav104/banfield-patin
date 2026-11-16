import { Link, useLocation } from 'react-router-dom';
import { NOT_FOUND_TEXT, NOT_FOUND_TITLE } from './messages';
import { athleteStatusFilterSchema } from './schemas';

/**
 * Where «Volver al listado» goes: the list the administrator came from, with its status filter and page. The list passes them in
 * the navigation state and ONLY those two values (never a search term, a name or a DNI); anything else is dropped here.
 */
export function backToListPath(state: unknown): string {
  const raw = (state as { listSearch?: unknown } | null)?.listSearch;
  if (typeof raw !== 'string') return '/admin/deportistas';
  const source = new URLSearchParams(raw);
  const query = new URLSearchParams();
  const estado = athleteStatusFilterSchema.safeParse(source.get('estado'));
  if (estado.success) query.set('estado', estado.data);
  const page = source.get('pagina');
  if (page !== null && /^[1-9][0-9]{0,5}$/.test(page)) query.set('pagina', page);
  const text = query.toString();
  return text === '' ? '/admin/deportistas' : `/admin/deportistas?${text}`;
}

export function BackToListLink() {
  const location = useLocation();
  return (
    <Link to={backToListPath(location.state)} className="inline-flex min-h-11 items-center text-sm font-semibold text-brand-700 underline-offset-4 hover:underline">
      Volver al listado
    </Link>
  );
}

/** One screen for an athlete that does not exist, belongs to another school or has a malformed id: it never says which. */
export function AthleteNotFound() {
  return (
    <div className="max-w-2xl space-y-4">
      <BackToListLink />
      <section className="rounded-2xl border border-dashed border-line bg-white p-6">
        <h1 className="text-xl font-bold">{NOT_FOUND_TITLE}</h1>
        <p className="mt-2 text-sm leading-relaxed text-muted">{NOT_FOUND_TEXT}</p>
      </section>
    </div>
  );
}
