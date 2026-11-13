import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { AthleteCard } from './AthleteCard';
import { useFamilyAccount } from './account';
import { ATHLETES_PREVIEW_SIZE, isFamilyUnavailable } from './api';
import { ErrorBlock, FamilyUnavailableNotice, LoadingBlock, NoAthletesNotice, NoFamilyNotice } from './FamilyStates';
import { useAthletes, useMyFamily } from './hooks';
import { identityKey, type FamilyIdentity } from './identity';

function FamilyCard({ identity }: { identity: FamilyIdentity }) {
  const family = useMyFamily(identity);
  if (family.isPending) return <LoadingBlock label="Cargando tu familia…" />;
  if (family.isError) return <ErrorBlock error={family.error} onRetry={() => void family.refetch()} />;
  const { nombreReferencia, tutores } = family.data;

  return (
    <section aria-labelledby="family-title" className="rounded-2xl border border-line bg-white p-5">
      <h2 id="family-title" className="text-lg font-bold">
        Tu familia
      </h2>
      {nombreReferencia ? <p className="mt-1 break-words text-base font-medium">{nombreReferencia}</p> : null}
      {tutores.length > 0 ? (
        <ul className="mt-4 space-y-2">
          {tutores.map((tutor) => (
            <li key={tutor.id} className="text-sm">
              <span className="break-words font-semibold">
                {tutor.nombre} {tutor.apellido}
              </span>
              {tutor.parentesco ? <span className="text-muted"> · {tutor.parentesco}</span> : null}
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-3 text-sm text-muted">Todavía no hay tutores registrados.</p>
      )}
    </section>
  );
}

function AthletesSummary({ identity }: { identity: FamilyIdentity }) {
  const athletes = useAthletes(identity, 0, ATHLETES_PREVIEW_SIZE);
  if (athletes.isPending) return <LoadingBlock label="Cargando deportistas…" />;
  if (athletes.isError) return <ErrorBlock error={athletes.error} onRetry={() => void athletes.refetch()} />;
  const { contenido, totalElementos } = athletes.data;
  if (totalElementos === 0) return <NoAthletesNotice />;

  return (
    <section aria-labelledby="athletes-title" className="space-y-3">
      <div className="flex items-baseline justify-between gap-3">
        <h2 id="athletes-title" className="text-lg font-bold">
          Deportistas
        </h2>
        <p className="text-sm text-muted">{totalElementos === 1 ? '1 vinculado' : `${totalElementos} vinculados`}</p>
      </div>
      <ul className="space-y-3">
        {contenido.map((athlete) => (
          <AthleteCard key={athlete.id} athlete={athlete} />
        ))}
      </ul>
      <Button asChild variant="outline" block>
        <Link to="/familia/deportistas">
          {totalElementos > contenido.length ? `Ver todos los deportistas (${totalElementos})` : 'Ver todos los deportistas'}
        </Link>
      </Button>
    </section>
  );
}

function FamilySections({ identity }: { identity: FamilyIdentity }) {
  // Same key as FamilyCard: one request, shared. A 404 here means the family itself is not available.
  const family = useMyFamily(identity);
  if (isFamilyUnavailable(family.error)) return <FamilyUnavailableNotice />;
  return (
    <>
      <FamilyCard identity={identity} />
      <AthletesSummary identity={identity} />
    </>
  );
}

/** Family dashboard (/familia): real family data and a short preview of the linked athletes. Read-only. */
export function FamilyDashboardPage() {
  const account = useFamilyAccount();
  if (!account) return null;

  return (
    <div className="space-y-6">
      <section>
        <p className="text-sm font-semibold text-brand-700">Portal de familias</p>
        <h1 className="mt-1 break-words text-3xl font-extrabold tracking-tight">Hola, {account.user.nombre}</h1>
        <p className="mt-2 text-muted">Acá ves un resumen de tu familia en la escuela.</p>
      </section>
      {/* key: another identity gets a fresh subtree, never the previous one's state */}
      {account.familyId ? <FamilySections key={identityKey(account.identity)} identity={account.identity} /> : <NoFamilyNotice />}
    </div>
  );
}
