import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import type { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts, which mirrors POST /api/admin/deportistas). Real-backend evidence lives in
// e2e/deportistas-consulta-alta.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const DNI = '30111322';
const CUIL = '20301113227';
const location = () => screen.getByTestId('location').textContent ?? '';
const posts = () => backend.requests.filter((r) => r.method === 'post' && r.url === '/admin/deportistas');
const listGets = () => backend.requests.filter((r) => r.method === 'get' && /^\/admin\/deportistas\?/.test(r.url));
const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function openForm(): Promise<{ queryClient: QueryClient }> {
  backend.signIn('marcos@example.com');
  const rendered = renderApp('/admin/deportistas/nuevo');
  await screen.findByRole('heading', { name: 'Nuevo deportista' });
  return rendered;
}

type Data = Partial<Record<'dni' | 'nombre' | 'apellido' | 'cuil' | 'nacimiento' | 'nacionalidad' | 'telefono' | 'correo' | 'domicilio' | 'otros' | 'localidad' | 'partido' | 'cp', string>>;

const LABELS: Record<keyof Data, RegExp> = {
  dni: /^DNI/,
  nombre: /^Nombre/,
  apellido: /^Apellido/,
  cuil: /^CUIL/,
  nacimiento: /^Fecha de nacimiento/,
  nacionalidad: /^Nacionalidad/,
  telefono: /^Teléfono/,
  correo: /^Correo federativo/,
  domicilio: /^Domicilio \(opcional\)/,
  otros: /^Otros datos del domicilio/,
  localidad: /^Localidad/,
  partido: /^Partido/,
  cp: /^Código postal/,
};

async function fill(user: UserEvent, data: Data) {
  for (const [key, value] of Object.entries(data) as [keyof Data, string][]) {
    const input = screen.getByLabelText(LABELS[key]);
    if (key === 'nacimiento') fireEvent.change(input, { target: { value } });
    else await user.type(input, value);
  }
}

const required: Data = { dni: DNI, nombre: 'Lucía', apellido: 'Sintética' };
const submit = (user: UserEvent) => user.click(screen.getByRole('button', { name: 'Crear deportista' }));

describe('new athlete: entry points (mock backend)', () => {
  it('is reached from the list, with no write request until it is submitted', async () => {
    backend.signIn('marcos@example.com');
    renderApp('/admin/deportistas');
    await userEvent.setup().click(await screen.findByRole('link', { name: 'Nuevo deportista' }));
    expect(await screen.findByRole('heading', { name: 'Nuevo deportista' })).toBeInTheDocument();
    expect(location()).toBe('/admin/deportistas/nuevo');
    expect(posts()).toHaveLength(0);
  });

  it('keeps a family user out', async () => {
    backend.signIn('lucia@example.com');
    renderApp('/admin/deportistas/nuevo');
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Nuevo deportista' })).not.toBeInTheDocument();
  });

  it('says the athlete is created without a family, an account or an invitation', async () => {
    await openForm();
    expect(screen.getByText(/se crea sin familia/)).toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/familia/i)).not.toBeInTheDocument();
  });

  it('labels every field, marks the three required ones and sizes the inputs to the backend limits', async () => {
    await openForm();
    for (const label of Object.values(LABELS)) expect(screen.getByLabelText(label)).toBeInTheDocument();
    for (const name of [/^DNI/, /^Nombre/, /^Apellido/]) expect(screen.getByLabelText(name)).toBeRequired();
    expect(screen.getByLabelText(/^CUIL/)).not.toBeRequired();
    const limits: [RegExp, string][] = [[/^DNI/, '20'], [/^Nombre/, '100'], [/^Apellido/, '100'], [/^CUIL/, '20'], [/^Nacionalidad/, '80'], [/^Teléfono/, '40'], [/^Correo federativo/, '180'], [/^Domicilio \(opcional\)/, '180'], [/^Otros datos/, '250'], [/^Localidad/, '100'], [/^Partido/, '100'], [/^Código postal/, '15']];
    for (const [label, max] of limits) expect(screen.getByLabelText(label)).toHaveAttribute('maxlength', max);
  });
});

describe('new athlete: validation (mock backend)', () => {
  it('requires DNI, name and last name, shows each error next to its field and sends nothing', async () => {
    await openForm();
    await submit(userEvent.setup());
    expect(await screen.findByText('Ingresá el DNI.')).toBeInTheDocument();
    expect(screen.getByText('Ingresá el nombre.')).toBeInTheDocument();
    expect(screen.getByText('Ingresá el apellido.')).toBeInTheDocument();
    const dni = screen.getByLabelText(/^DNI/);
    expect(dni).toHaveAttribute('aria-invalid', 'true');
    expect(dni).toHaveAccessibleDescription(expect.stringContaining('Ingresá el DNI.'));
    expect(posts()).toHaveLength(0);
  });

  it('treats blank spaces as missing', async () => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, { dni: '   ', nombre: '  ', apellido: ' ' });
    await submit(user);
    expect(await screen.findByText('Ingresá el nombre.')).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it.each([
    ['too short', '123456'],
    ['too long', '1234567890'],
    ['letters', '30A11322'],
  ])('rejects a DNI that is %s', async (_name, dni) => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, { ...required, dni });
    await submit(user);
    expect(await screen.findByText('El DNI debe tener entre 7 y 9 dígitos.')).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it('accepts a DNI typed with dots', async () => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, { ...required, dni: '30.111.322' });
    await submit(user);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(posts()[0]!.body).toMatchObject({ dni: '30.111.322' }); // the backend normalizes it
  });

  it.each([
    ['a wrong check digit', '20301113228'],
    ['too few digits', '2030111322'],
    ['letters', '20-30111322-X'],
  ])('rejects a CUIL with %s', async (_name, cuil) => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, { ...required, cuil });
    await submit(user);
    expect(await screen.findByText('El CUIL debe tener 11 dígitos y un dígito verificador válido.')).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it('accepts a CUIL typed with hyphens', async () => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, { ...required, cuil: '20-30111322-7' });
    await submit(user);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
  });

  it('rejects a birth date that is today or in the future, and an invalid e-mail', async () => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, { ...required, nacimiento: '2999-01-01', correo: 'no-es-un-correo' });
    await submit(user);
    expect(await screen.findByText('Ingresá una fecha de nacimiento válida y anterior a hoy.')).toBeInTheDocument();
    expect(screen.getByText('Ingresá un correo electrónico válido.')).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it('clears the error of a field as soon as it is edited', async () => {
    await openForm();
    const user = userEvent.setup();
    await submit(user);
    await screen.findByText('Ingresá el nombre.');
    await user.type(screen.getByLabelText(/^Nombre/), 'A');
    expect(screen.queryByText('Ingresá el nombre.')).not.toBeInTheDocument();
  });
});

describe('new athlete: creation (mock backend)', () => {
  it('sends only the three required fields when the rest are empty, and opens the detail after the 201', async () => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, required);
    await submit(user);
    expect(await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' })).toBeInTheDocument();
    expect(posts()).toHaveLength(1);
    expect(posts()[0]!.body).toEqual({ dni: DNI, nombre: 'Lucía', apellido: 'Sintética' });
    expect(location()).toMatch(/^\/admin\/deportistas\/dep-\d+$/);
    expect(screen.getByText('Deportista creado. Todavía no tiene familia asignada.')).toBeInTheDocument();
    expect(backend.adminAthleteCount()).toBe(1);
  });

  it('sends every filled field trimmed, never a blank optional, and shows what the backend stored', async () => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, {
      dni: `  ${DNI} `,
      nombre: ' Lucía ',
      apellido: 'Sintética',
      cuil: CUIL,
      nacimiento: '2012-05-04',
      nacionalidad: 'Argentina',
      telefono: '11 5555-0000',
      correo: ' LUCIA@Example.test ',
      domicilio: 'Calle Falsa 123',
      otros: '   ',
      localidad: 'Banfield',
      partido: 'Lomas de Zamora',
      cp: '1828',
    });
    await submit(user);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(posts()[0]!.body).toEqual({
      dni: DNI,
      nombre: 'Lucía',
      apellido: 'Sintética',
      cuil: CUIL,
      fechaNacimiento: '2012-05-04',
      nacionalidad: 'Argentina',
      telefonoContacto: '11 5555-0000',
      emailFederativo: 'LUCIA@Example.test',
      domicilio: 'Calle Falsa 123',
      localidad: 'Banfield',
      partido: 'Lomas de Zamora',
      codigoPostal: '1828',
    }); // `otrosDatosDomicilio` (blank) does not travel
    expect(document.body.textContent).toContain('lucia@example.test'); // the backend lowercases the e-mail
    expect(document.body.textContent).toContain('04/05/2012');
    expect(within(screen.getByRole('main')).getAllByText('No informado')).toHaveLength(1); // only «Otros datos del domicilio»
  });

  it('creates the athlete without any family link, account or invitation', async () => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, required);
    await submit(user);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(backend.writes().map((r) => `${r.method} ${r.url}`)).toEqual(['post /admin/deportistas']);
  });

  it('shows the new athlete in the list after the creation (the list is refreshed from the server)', async () => {
    backend.signIn('marcos@example.com');
    renderApp('/admin/deportistas');
    const user = userEvent.setup();
    await screen.findByText('Todavía no hay deportistas registrados.');
    await user.click(screen.getByRole('link', { name: 'Nuevo deportista' }));
    await screen.findByRole('heading', { name: 'Nuevo deportista' });
    await fill(user, required);
    await submit(user);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    await user.click(screen.getByRole('link', { name: 'Volver al listado' }));
    expect(await screen.findByText('Sintética, Lucía')).toBeInTheDocument();
    expect(screen.getByText('•••••322')).toBeInTheDocument();
  });

  it('does not navigate, nor claim success, until the backend confirms', async () => {
    await openForm();
    const held = backend.hold('post', '/admin/deportistas');
    const user = userEvent.setup();
    await fill(user, required);
    await submit(user);
    expect(await screen.findByRole('button', { name: 'Creando…' })).toBeDisabled();
    expect(location()).toBe('/admin/deportistas/nuevo');
    expect(screen.queryByText(/Deportista creado/)).not.toBeInTheDocument();
    held.release();
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
  });

  it('sends a single request however many times the form is submitted while one is in flight', async () => {
    await openForm();
    const held = backend.hold('post', '/admin/deportistas');
    const user = userEvent.setup();
    await fill(user, required);
    await submit(user);
    await screen.findByRole('button', { name: 'Creando…' });
    fireEvent.submit(screen.getByRole('form', { name: 'Nuevo deportista' }));
    fireEvent.submit(screen.getByRole('form', { name: 'Nuevo deportista' }));
    await user.keyboard('{Enter}');
    held.release();
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(posts()).toHaveLength(1);
    expect(backend.adminAthleteCount()).toBe(1);
  });

  it('never saves by itself: nothing is sent while the administrator types', async () => {
    await openForm();
    await fill(userEvent.setup(), { ...required, cuil: CUIL });
    await act(async () => wait(600));
    expect(posts()).toHaveLength(0);
  });
});

describe('new athlete: duplicates and server errors (mock backend)', () => {
  it('tells an active duplicate DNI at the DNI field, keeps the draft and does not navigate', async () => {
    backend.addAdminAthlete({ nombre: 'Ya', apellido: 'Existente', dni: DNI });
    await openForm();
    const user = userEvent.setup();
    await fill(user, required);
    await submit(user);
    expect(await screen.findByText('Ya existe un deportista activo con ese DNI. Buscalo en el listado en lugar de crear uno nuevo.')).toBeInTheDocument();
    expect(screen.getByLabelText(/^DNI/)).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Lucía');
    expect(location()).toBe('/admin/deportistas/nuevo');
    expect(posts()).toHaveLength(1);
    expect(backend.adminAthleteCount()).toBe(1);
  });

  it('tells a DNI reserved by an INACTIVE athlete with its own message (not the duplicate one)', async () => {
    backend.addAdminAthlete({ nombre: 'Ya', apellido: 'Inactivo', dni: DNI, activo: false });
    await openForm();
    const user = userEvent.setup();
    await fill(user, required);
    await submit(user);
    expect(await screen.findByText(/Ya existe un deportista inactivo con ese DNI y el DNI sigue reservado/)).toBeInTheDocument();
    expect(screen.getByText(/filtro «Inactivos»/)).toBeInTheDocument();
    expect(screen.queryByText(/deportista activo con ese DNI/)).not.toBeInTheDocument();
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Lucía');
    expect(location()).toBe('/admin/deportistas/nuevo');
  });

  it('tells a duplicate CUIL at the CUIL field, keeping the DNI error out', async () => {
    backend.addAdminAthlete({ nombre: 'Ya', apellido: 'Existente', dni: '41000000', cuil: CUIL });
    await openForm();
    const user = userEvent.setup();
    await fill(user, { ...required, cuil: CUIL });
    await submit(user);
    expect(await screen.findByText('Ya existe un deportista con ese CUIL. Revisá el número o buscalo en el listado.')).toBeInTheDocument();
    expect(screen.getByLabelText(/^CUIL/)).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText(/^DNI/)).not.toHaveAttribute('aria-invalid');
    expect(location()).toBe('/admin/deportistas/nuevo');
  });

  it('can be corrected and sent again after a duplicate (a definite answer, not an uncertain one)', async () => {
    backend.addAdminAthlete({ nombre: 'Ya', apellido: 'Existente', dni: DNI });
    await openForm();
    const user = userEvent.setup();
    await fill(user, required);
    await submit(user);
    await screen.findByText(/Ya existe un deportista activo/);
    await user.clear(screen.getByLabelText(/^DNI/));
    await user.type(screen.getByLabelText(/^DNI/), '30111399');
    await submit(user);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(posts()).toHaveLength(2);
  });

  it('shows a generic validation message for a 400 of the server, without echoing its detail', async () => {
    backend.override('post', '/admin/deportistas', { status: 400, body: { codigo: 'VALIDACION', mensaje: 'detalle interno', detalles: [{ campo: 'dni', mensaje: 'detalle del servidor' }] } });
    await openForm();
    const user = userEvent.setup();
    await fill(user, required);
    await submit(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('Revisá los datos del deportista');
    expect(document.body).not.toHaveTextContent(/detalle interno|detalle del servidor/);
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Lucía');
  });

  it('explains a permission error (403) and keeps the draft', async () => {
    backend.override('post', '/admin/deportistas', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    await openForm();
    const user = userEvent.setup();
    await fill(user, required);
    await submit(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('No tenés permiso para crear deportistas.');
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Lucía');
  });

  it('sends a session that ended (401) to the admin login instead of showing the form', async () => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, required);
    backend.expireSession();
    await submit(user);
    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
    expect(backend.adminAthleteCount()).toBe(0);
  });

  it('tells an unrelated conflict as «otra operación en curso», nothing applied, and allows a manual retry', async () => {
    backend.override('post', '/admin/deportistas', { status: 409, body: { codigo: 'CONFLICTO_CONCURRENCIA', mensaje: 'x' } }, { once: true });
    await openForm();
    const user = userEvent.setup();
    await fill(user, required);
    await submit(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('Otra operación está en curso.');
    expect(screen.getByRole('button', { name: 'Crear deportista' })).toBeEnabled();
    await submit(user);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(posts()).toHaveLength(2);
  });

  it('explains a CSRF failure with a safe text', async () => {
    backend.override('post', '/admin/deportistas', { status: 403, body: { codigo: 'CSRF_INVALIDO', mensaje: 'detalle interno' } });
    await openForm();
    const user = userEvent.setup();
    await fill(user, required);
    await submit(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos validar la solicitud');
    expect(document.body).not.toHaveTextContent('detalle interno');
  });
});

describe('new athlete: an answer that cannot be confirmed (mock backend)', () => {
  async function submitLost(kind: 'network' | { status: number; body: unknown }, apply = false) {
    backend.override('post', '/admin/deportistas', kind, { once: true });
    await openForm();
    const user = userEvent.setup();
    await fill(user, required);
    if (apply) backend.addAdminAthleteBehindTheScenes({ nombre: 'Lucía', apellido: 'Sintética', dni: DNI });
    await submit(user);
    await screen.findByText(/No pudimos confirmar si el deportista se creó/);
    return user;
  }

  it.each([
    ['a lost response (network)', 'network' as const],
    ['a 500', { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'detalle interno' } }],
    ['a 503', { status: 503, body: { codigo: 'NO_DISPONIBLE', mensaje: 'x' } }],
    ['a 504', { status: 504, body: { codigo: 'TIMEOUT', mensaje: 'x' } }],
    ['a 201 whose body cannot be read', { status: 201, body: { basura: true } }],
  ])('does not say it failed, does not say it was created, does not retry and blocks the resend after %s', async (_name, kind) => {
    const user = await submitLost(kind);
    await act(async () => wait(700));
    expect(posts()).toHaveLength(1); // no automatic retry
    expect(location()).toBe('/admin/deportistas/nuevo'); // no navigation
    expect(screen.queryByText(/Deportista creado/)).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('detalle interno');
    expect(screen.getByRole('button', { name: 'Crear deportista' })).toBeDisabled();
    await user.keyboard('{Enter}');
    fireEvent.submit(screen.getByRole('form', { name: 'Nuevo deportista' }));
    expect(posts()).toHaveLength(1);
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Lucía'); // the draft is still there
    expect(screen.getByLabelText(/^Nombre/)).toBeDisabled();
  });

  it('verifies by DNI in a read-only search and presents a match as a hint, never as confirmation', async () => {
    const user = await submitLost('network', true);
    await user.click(screen.getByRole('button', { name: 'Verificar en el listado' }));
    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(group).toHaveTextContent('Encontramos un deportista con ese DNI. Puede ser el que intentaste crear o uno que ya existía: no podemos confirmarlo.');
    expect(within(group).getByRole('link', { name: 'Sintética, Lucía' })).toHaveAttribute('href', expect.stringMatching(/^\/admin\/deportistas\/dep-\d+$/));
    expect(group).toHaveTextContent('•••••322');
    expect(group).not.toHaveTextContent(DNI);
    expect(screen.queryByText(/Deportista creado|se creó correctamente/)).not.toBeInTheDocument();
    expect(location()).toBe('/admin/deportistas/nuevo');
    const search = new URLSearchParams(listGets().at(-1)!.url.split('?')[1]);
    expect(search.get('estado')).toBe('TODOS');
    expect(search.get('busqueda')).toBe(DNI);
    expect(search.get('page')).toBe('0');
    expect(search.get('size')).toBe('100'); // the most the backend admits
    expect(backend.writes().filter((r) => r.url.startsWith('/admin/deportistas'))).toHaveLength(1); // the verification wrote nothing
  });

  it('does NOT treat a same-name athlete with another DNI as the one we tried to create', async () => {
    backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: '41555666' }); // homonym
    const user = await submitLost('network');
    await user.click(screen.getByRole('button', { name: 'Verificar en el listado' }));
    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(group).toHaveTextContent('No observamos un deportista con ese DNI en este momento.');
    expect(group).toHaveTextContent('Eso no prueba que la solicitud original no se haya aplicado');
    expect(group).not.toHaveTextContent('incompleta');
    expect(within(group).queryByRole('link')).not.toBeInTheDocument();
  });

  it('does NOT report a different athlete whose DNI merely STARTS with the same digits (the search is by prefix)', async () => {
    backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: `${DNI}9` }); // 9 digits, same name: returned by the prefix search
    const user = await submitLost('network');
    await user.click(screen.getByRole('button', { name: 'Verificar en el listado' }));
    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(listGets().at(-1)!.url).toContain(`busqueda=${DNI}`);
    expect(group).toHaveTextContent('No observamos un deportista con ese DNI en este momento.');
    expect(within(group).queryByRole('link')).not.toBeInTheDocument();
  });

  /** 7-digit DNI «3011132»: the prefix search also returns every 8- and 9-digit DNI that starts with it (up to 110 others). */
  const SHORT_DNI = '3011132';
  function seedNeighbours(count: number) {
    const dnis: string[] = [];
    for (let tens = 0; tens < 10; tens += 1) {
      dnis.push(`${SHORT_DNI}${tens}`);
      for (let units = 0; units < 10; units += 1) dnis.push(`${SHORT_DNI}${tens}${units}`);
    }
    dnis.slice(0, count).forEach((dni, index) => backend.addAdminAthlete({ nombre: 'Vecino', apellido: `Aaa ${String(index).padStart(3, '0')}`, dni }));
  }
  async function submitShortDniLost(apellido: string, apply: boolean) {
    backend.override('post', '/admin/deportistas', 'network', { once: true });
    await openForm();
    const user = userEvent.setup();
    await fill(user, { dni: SHORT_DNI, nombre: 'Lucía', apellido });
    if (apply) backend.addAdminAthleteBehindTheScenes({ nombre: 'Lucía', apellido, dni: SHORT_DNI });
    await submit(user);
    await screen.findByText(/No pudimos confirmar si el deportista se creó/);
    return user;
  }

  it('finds the athlete even when it is beyond the first 20 candidates, because the check asks for up to 100', async () => {
    seedNeighbours(30); // 31 candidates in total, the one we look for sorted last
    const user = await submitShortDniLost('Zeta', true);
    await user.click(screen.getByRole('button', { name: 'Verificar en el listado' }));
    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(within(group).getByRole('link', { name: 'Zeta, Lucía' })).toBeInTheDocument();
    expect(group).toHaveTextContent('no podemos confirmarlo');
    expect(group).not.toHaveTextContent('incompleta');
    expect(new URLSearchParams(listGets().at(-1)!.url.split('?')[1]).get('size')).toBe('100');
  });

  it('says the check is INCOMPLETE, and concludes nothing, when more than 100 candidates exist and none inspected matches', async () => {
    seedNeighbours(110); // 111 candidates; the one we look for is sorted last: the 100 inspected do not include it
    const user = await submitShortDniLost('Zeta', true);
    await user.click(screen.getByRole('button', { name: 'Verificar en el listado' }));
    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(group).toHaveTextContent('La verificación es incompleta: la búsqueda devolvió 111 resultados y solo pudimos revisar 100.');
    expect(group).toHaveTextContent('No podemos descartar que el deportista esté entre los que no revisamos');
    expect(group).not.toHaveTextContent('No observamos');
    expect(group).not.toHaveTextContent('No encontramos');
    expect(within(group).queryByRole('link')).not.toBeInTheDocument();
    expect(listGets().filter((r) => r.url.includes(`busqueda=${SHORT_DNI}`))).toHaveLength(1); // one page: no walk through the pages
    expect(screen.getByRole('button', { name: 'Crear deportista' })).toBeDisabled(); // an incomplete check does not reopen the form
    expect(posts()).toHaveLength(1);
  });

  it('shows a match found among the first 100 as a hint and still says that more results were not inspected', async () => {
    seedNeighbours(110);
    const user = await submitShortDniLost('Aaa', true); // sorted among the first 100
    await user.click(screen.getByRole('button', { name: 'Verificar en el listado' }));
    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(within(group).getByRole('link', { name: 'Aaa, Lucía' })).toBeInTheDocument();
    expect(group).toHaveTextContent('no podemos confirmarlo');
    expect(group).toHaveTextContent('La verificación es incompleta');
    expect(screen.queryByText(/Deportista creado/)).not.toBeInTheDocument();
  });

  it('with more than 100 candidates and no exact match it never states that the request was not applied, and an explicit retry is still the only way to resend', async () => {
    seedNeighbours(110);
    const user = await submitShortDniLost('Zeta', false);
    await user.click(screen.getByRole('button', { name: 'Verificar en el listado' }));
    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(group).not.toHaveTextContent(/no se aplicó|no se creó|no fue creado/i);
    await act(async () => wait(500));
    expect(posts()).toHaveLength(1); // nothing was resent by itself
    await user.click(within(group).getByRole('button', { name: 'Volver a intentar' }));
    expect(posts()).toHaveLength(1);
    await submit(user);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Zeta' });
    expect(posts()).toHaveLength(2);
  });

  it('keeps the resend blocked when the verification itself fails', async () => {
    const user = await submitLost('network');
    backend.override('get', `/admin/deportistas?estado=TODOS&page=0&size=100&busqueda=${DNI}`, 'network', { once: true });
    await user.click(screen.getByRole('button', { name: 'Verificar en el listado' }));
    expect(await screen.findByText('No pudimos verificar el listado ahora. Intentá de nuevo en unos instantes.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Crear deportista' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Verificar en el listado' })).toBeEnabled();
  });

  it('after verifying that nothing is there, retrying is an explicit choice and then creates it once', async () => {
    const user = await submitLost('network');
    await user.click(screen.getByRole('button', { name: 'Verificar en el listado' }));
    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(group).toHaveTextContent('el sistema lo rechazará por DNI duplicado: no se crea dos veces');
    expect(screen.getByRole('button', { name: 'Crear deportista' })).toBeDisabled(); // verifying alone does not reopen the form
    await user.click(within(group).getByRole('button', { name: 'Volver a intentar' }));
    expect(screen.getByLabelText(/^Nombre/)).toBeEnabled();
    expect(posts()).toHaveLength(1); // choosing to retry is not sending
    await submit(user);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(posts()).toHaveLength(2);
    expect(backend.adminAthleteCount()).toBe(1);
  });

  it('when the first attempt WAS applied, an explicit retry cannot duplicate it: the DNI is rejected, and the message says it may be ours', async () => {
    const user = await submitLost('network', true);
    await user.click(screen.getByRole('button', { name: 'Verificar en el listado' }));
    await user.click(within(await screen.findByRole('group', { name: 'Verificación' })).getByRole('button', { name: 'Volver a intentar' }));
    await submit(user);
    expect(await screen.findByText(/Ya existe un deportista activo con ese DNI/)).toBeInTheDocument();
    expect(screen.getByText(/Como el intento anterior no se pudo confirmar, es posible que ese deportista sea el que intentaste crear/)).toBeInTheDocument();
    expect(backend.adminAthleteCount()).toBe(1); // never two
    expect(location()).toBe('/admin/deportistas/nuevo');
  });

  it('does not add the «may be ours» hint to a duplicate that follows a DEFINITE answer', async () => {
    backend.addAdminAthlete({ nombre: 'Ya', apellido: 'Existente', dni: DNI });
    await openForm();
    const user = userEvent.setup();
    await fill(user, required);
    await submit(user);
    await screen.findByText(/Ya existe un deportista activo/);
    expect(screen.queryByText(/Como el intento anterior no se pudo confirmar/)).not.toBeInTheDocument();
  });
});

describe('new athlete: unsaved changes, identity and privacy (mock backend)', () => {
  it('does not warn about an untouched form and cancels back to the list', async () => {
    await openForm();
    await userEvent.setup().click(screen.getByRole('link', { name: 'Cancelar' }));
    expect(await screen.findByRole('heading', { name: 'Deportistas' })).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it('warns before leaving a draft by a link, and lets the administrator keep editing or discard', async () => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Borrador' });
    await user.click(screen.getByRole('link', { name: 'Cancelar' }));
    const panel = await screen.findByRole('group', { name: 'Cambios sin guardar' });
    expect(location()).toBe('/admin/deportistas/nuevo');
    await user.click(within(panel).getByRole('button', { name: 'Seguir editando' }));
    expect(screen.queryByRole('group', { name: 'Cambios sin guardar' })).not.toBeInTheDocument();
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Borrador');
    await user.click(screen.getByRole('link', { name: 'Deportistas' }));
    await user.click(within(await screen.findByRole('group', { name: 'Cambios sin guardar' })).getByRole('button', { name: 'Descartar y salir' }));
    expect(await screen.findByRole('heading', { name: 'Deportistas' })).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it('asks the browser to confirm when the tab is closed with a draft, and not otherwise', async () => {
    await openForm();
    const clean = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(clean);
    expect(clean.defaultPrevented).toBe(false);
    await fill(userEvent.setup(), { nombre: 'Borrador' });
    const dirty = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);
  });

  it('drops the draft when the session now belongs to another administrator', async () => {
    backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
    const { queryClient } = await openForm();
    await fill(userEvent.setup(), { ...required, cuil: CUIL });
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Lucía');
    backend.signIn('otra.admin@example.com');
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });
    await screen.findByRole('heading', { name: 'Nuevo deportista' });
    await waitFor(() => expect(screen.getByLabelText(/^Nombre/)).toHaveValue(''));
    expect(screen.getByLabelText(/^DNI/)).toHaveValue('');
    expect(document.body.textContent).not.toContain(CUIL);
    expect(posts()).toHaveLength(0);
  });

  it('keeps the draft out of the address, storage and console, also across a failed attempt', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    const pattern = new RegExp(`${DNI}|${CUIL}|Lucía|Sintética|lucia@|5555|Falsa`);
    try {
      backend.override('post', '/admin/deportistas', 'network', { once: true });
      await openForm();
      const user = userEvent.setup();
      await fill(user, { ...required, cuil: CUIL, correo: 'lucia@example.test', telefono: '11 5555-0000', domicilio: 'Calle Falsa 123' });
      await submit(user);
      await screen.findByText(/No pudimos confirmar si el deportista se creó/);
      await user.click(screen.getByRole('button', { name: 'Verificar en el listado' }));
      await screen.findByRole('group', { name: 'Verificación' });
      expect(location()).not.toMatch(pattern);
      expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(pattern);
      expect(spies.flatMap((spy) => spy.mock.calls).flat().join(' ')).not.toMatch(pattern);
    } finally {
      spies.forEach((spy) => spy.mockRestore());
    }
  });
});
