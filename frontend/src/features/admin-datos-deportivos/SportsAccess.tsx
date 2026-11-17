import { Button } from '@/components/ui/button';
import { SECTION_LINK, SECTION_TEXT, SECTION_TITLE } from './messages';
import { sportsHistoryPath } from './navigation';
import { SportsLink } from './SportsUi';

/** The way into the sports data from the athlete detail. It only leads to the history: nothing here changes the state in the school. */
export function SportsAccess({ athleteId }: { athleteId: string }) {
  return (
    <section aria-label={SECTION_TITLE} className="rounded-2xl border border-line bg-white p-5">
      <h2 className="text-base font-bold">{SECTION_TITLE}</h2>
      <p className="mt-1 text-sm text-muted">{SECTION_TEXT}</p>
      <Button asChild variant="outline" className="mt-3">
        <SportsLink to={sportsHistoryPath(athleteId)} className="">
          {SECTION_LINK}
        </SportsLink>
      </Button>
    </section>
  );
}
