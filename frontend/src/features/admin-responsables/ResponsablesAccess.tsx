import { Button } from '@/components/ui/button';
import { SportsLink } from '@/features/admin-datos-deportivos/SportsUi';
import { ACCESS_LINK, ACCESS_TEXT, ACCESS_TITLE } from './messages';
import { athleteResponsablesPath } from './navigation';

/** The way into the responsables of an athlete, from its detail. It only leads to a read-only screen. */
export function ResponsablesAccess({ athleteId }: { athleteId: string }) {
  return (
    <section aria-label={ACCESS_TITLE} className="rounded-2xl border border-line bg-white p-5">
      <h2 className="text-base font-bold">{ACCESS_TITLE}</h2>
      <p className="mt-1 text-sm text-muted">{ACCESS_TEXT}</p>
      <Button asChild variant="outline" className="mt-3">
        <SportsLink to={athleteResponsablesPath(athleteId)} className="">
          {ACCESS_LINK}
        </SportsLink>
      </Button>
    </section>
  );
}
