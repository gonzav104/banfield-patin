import { Link } from 'react-router-dom';
import { NOT_FOUND_TEXT, NOT_FOUND_TITLE } from './messages';

export function BackToListLink() {
  return (
    <Link to="/admin/familias" className="inline-flex min-h-11 items-center text-sm font-semibold text-brand-700 underline-offset-4 hover:underline">
      Volver al listado
    </Link>
  );
}

/** One screen for a family that does not exist, belongs to another school or has a malformed id: it never says which. */
export function FamilyNotFound() {
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
