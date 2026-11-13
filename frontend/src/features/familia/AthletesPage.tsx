import { useEffect, useRef, useState } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { AthleteCard } from './AthleteCard';
import { useFamilyAccount } from './account';
import { ATHLETES_PAGE_SIZE, isFamilyUnavailable } from './api';
import { ErrorBlock, FamilyUnavailableNotice, LoadingBlock, NoAthletesNotice, NoFamilyNotice } from './FamilyStates';
import { useAthletes, useMyFamily } from './hooks';
import { identityKey, type FamilyIdentity } from './identity';
import { ATHLETE_STATE_NOTE } from './messages';
import { Pagination } from './Pagination';

function AthletesList({ identity }: { identity: FamilyIdentity }) {
  const [page, setPage] = useState(0);
  const athletes = useAthletes(identity, page, ATHLETES_PAGE_SIZE);
  // Only needed to tell "the family is not available" from "the family has no athletes".
  const family = useMyFamily(identity);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const firstRender = useRef(true);

  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    headingRef.current?.focus(); // after changing page, keyboard and screen-reader users land at the top of the list
  }, [page]);

  if (athletes.isError && !athletes.data) return <ErrorBlock error={athletes.error} onRetry={() => void athletes.refetch()} />;
  if (athletes.isPending) return <LoadingBlock label="Cargando deportistas…" />;

  const { contenido, totalElementos } = athletes.data;
  const totalPages = Math.max(1, Math.ceil(totalElementos / ATHLETES_PAGE_SIZE));

  if (totalElementos === 0) {
    if (family.isPending) return <LoadingBlock label="Cargando deportistas…" />;
    return isFamilyUnavailable(family.error) ? <FamilyUnavailableNotice /> : <NoAthletesNotice />;
  }

  return (
    <div className="space-y-4">
      <h2 ref={headingRef} tabIndex={-1} className="text-lg font-bold outline-none">
        Deportistas vinculados
      </h2>
      {athletes.isError ? <Alert tone="warning">No pudimos actualizar el listado. Mostramos la última información disponible.</Alert> : null}
      {contenido.length === 0 ? (
        <div className="space-y-3">
          <Alert tone="info">Esta página ya no tiene deportistas: el listado cambió.</Alert>
          <Button variant="outline" onClick={() => setPage(0)}>
            Volver a la primera página
          </Button>
        </div>
      ) : (
        <ul className="space-y-3" aria-busy={athletes.isFetching}>
          {contenido.map((athlete) => (
            <AthleteCard key={athlete.id} athlete={athlete} />
          ))}
        </ul>
      )}
      <Pagination
        page={page}
        totalPages={totalPages}
        total={totalElementos}
        onPrevious={() => setPage((value) => Math.max(0, value - 1))}
        onNext={() => setPage((value) => value + 1)}
      />
      <p className="text-sm leading-relaxed text-muted">{ATHLETE_STATE_NOTE}</p>
    </div>
  );
}

/** Linked athletes (/familia/deportistas), paginated with the backend's real pagination. Read-only. */
export function AthletesPage() {
  const account = useFamilyAccount();
  if (!account) return null;

  return (
    <div className="space-y-6">
      <section>
        <p className="text-sm font-semibold text-brand-700">Portal de familias</p>
        <h1 className="mt-1 text-3xl font-extrabold tracking-tight">Deportistas</h1>
        <p className="mt-2 text-muted">Los deportistas vinculados a tu familia.</p>
      </section>
      {/* key: another identity restarts at page 1 with fresh state */}
      {account.familyId ? <AthletesList key={identityKey(account.identity)} identity={account.identity} /> : <NoFamilyNotice />}
    </div>
  );
}
