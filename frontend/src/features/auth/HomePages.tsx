import { useSession } from './session';

function Identity({ rows }: { rows: [string, string][] }) {
  return (
    <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
      {rows.map(([term, value]) => (
        <div key={term}>
          <dt className="text-xs font-bold uppercase tracking-wider text-muted">{term}</dt>
          <dd className="mt-0.5 break-words font-medium">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Minimal family home: real identity only. Athletes, documents and raffles arrive in later units. */
export function FamiliaHomePage() {
  const session = useSession();
  if (session.status !== 'authenticated') return null;
  const { user } = session;
  return (
    <div className="space-y-6">
      <section>
        <p className="text-sm font-semibold text-brand-700">Portal de familias</p>
        <h1 className="mt-1 text-3xl font-extrabold tracking-tight">Hola, {user.nombre}</h1>
        <p className="mt-2 text-muted">Ingresaste correctamente. Desde acá vas a gestionar todo lo de tu familia en la escuela.</p>
      </section>
      <section aria-labelledby="account" className="rounded-2xl border border-line bg-white p-5">
        <h2 id="account" className="mb-4 text-lg font-bold">
          Tu cuenta
        </h2>
        <Identity
          rows={[
            ['Nombre', `${user.nombre} ${user.apellido}`],
            ['Correo', user.email],
          ]}
        />
      </section>
      <p className="rounded-2xl border border-dashed border-line p-5 text-sm text-muted">
        Las secciones de deportistas, documentación y competencias se habilitan en próximas etapas.
      </p>
    </div>
  );
}

/** Minimal admin home: real identity and session security state only. */
export function AdminHomePage() {
  const session = useSession();
  if (session.status !== 'authenticated') return null;
  const { user } = session;
  return (
    <div className="max-w-4xl space-y-6">
      <section>
        <p className="text-sm font-semibold text-brand-700">Panel administrativo</p>
        <h1 className="mt-1 text-3xl font-extrabold tracking-tight">Hola, {user.nombre}</h1>
      </section>
      <section aria-labelledby="account" className="rounded-2xl border border-line bg-white p-6">
        <h2 id="account" className="mb-4 text-lg font-bold">
          Tu sesión
        </h2>
        <Identity
          rows={[
            ['Nombre', `${user.nombre} ${user.apellido}`],
            ['Correo', user.email],
            ['Rol', 'Administrador'],
            ['Verificación en dos pasos', user.mfaEnrolado ? 'Activa' : 'Sin configurar'],
          ]}
        />
      </section>
      <p className="rounded-2xl border border-dashed border-line p-5 text-sm text-muted">
        Los módulos de deportistas, competencias y rifas se habilitan en próximas etapas.
      </p>
    </div>
  );
}
