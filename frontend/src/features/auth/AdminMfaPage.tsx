import { useQueryClient } from '@tanstack/react-query';
import QRCode from 'qrcode';
import { useEffect, useState } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import type { ApiError } from '@/lib/api-error';
import { AuthShell } from './AuthShell';
import { MfaCodeForm } from './MfaCodeForm';
import { commonErrorMessage } from './messages';
import {
  sessionKey,
  useConfirmMfa,
  useEnrollMfa,
  useLogout,
  useSession,
  useVerifyMfa,
} from './session';

function mfaErrorMessage(error: ApiError): string {
  if (error.kind === 'unauthenticated' && error.code === 'CODIGO_MFA_INVALIDO') {
    return 'El código no es correcto o ya fue usado. Ingresá el código vigente de tu app.';
  }
  if (error.kind === 'validation') return 'Ingresá los 6 dígitos del código.';
  if (error.kind === 'conflict') {
    return 'El estado de tu verificación cambió. Actualizamos la pantalla: revisá los pasos y volvé a intentar.';
  }
  return commonErrorMessage(error) ?? 'No pudimos verificar el código. Intentá de nuevo.';
}

function groupSecret(secret: string): string {
  return secret.replace(/(.{4})/g, '$1 ').trim();
}

function EnrollmentQr({ uri }: { uri: string }) {
  const [src, setSrc] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    QRCode.toDataURL(uri, { margin: 1, width: 224, errorCorrectionLevel: 'M' })
      .then((url) => {
        if (!cancelled) setSrc(url);
      })
      .catch(() => {
        if (!cancelled) setSrc(null);
      });
    return () => {
      cancelled = true;
    };
  }, [uri]);

  if (!src) return null;
  return (
    <img
      src={src}
      width={224}
      height={224}
      alt="Código QR para registrar la verificación en tu app autenticadora"
      className="mx-auto rounded-lg border border-line"
    />
  );
}

/** Administrator login, step 2 of 2: enrol the TOTP factor the first time, then enter the code on later logins. */
export function AdminMfaPage() {
  const session = useSession();
  const queryClient = useQueryClient();
  const logout = useLogout('/admin/login');
  const enroll = useEnrollMfa();
  const confirm = useConfirmMfa();
  const verify = useVerifyMfa();
  const [resetSignal, setResetSignal] = useState(0);

  if (session.status !== 'mfa-pending') return null;
  const { user } = session;
  const enrolled = user.mfaEnrolado;
  const codeMutation = enrolled ? verify : confirm;

  const onCode = (code: string) =>
    codeMutation.mutate(code, {
      onSuccess: () => enroll.reset(), // the secret must not outlive the enrolment
      onError: (error) => {
        setResetSignal((value) => value + 1);
        // A state mismatch means our copy of the session is stale: refresh it and let the screen redraw.
        if (error.kind === 'conflict') void queryClient.invalidateQueries({ queryKey: sessionKey });
      },
    });

  const enrollment = enroll.data;

  return (
    <AuthShell
      variant="admin"
      title={enrolled ? 'Verificación en dos pasos' : 'Configurá la verificación en dos pasos'}
      description={
        enrolled
          ? `Hola, ${user.nombre}. Ingresá el código de tu app autenticadora para terminar de ingresar.`
          : `Hola, ${user.nombre}. Las cuentas de administración necesitan un segundo factor. Lo configurás una sola vez.`
      }
      footer={
        <Button
          variant="ghost"
          className="text-white hover:bg-white/10"
          onClick={() => logout.mutate()}
          disabled={logout.isPending}
        >
          Ingresar con otra cuenta
        </Button>
      }
    >
      {session.connectionIssue ? (
        <Alert tone="warning" className="mb-5">
          No pudimos actualizar el estado de tu sesión. Si algo falla, reintentá en unos instantes.
        </Alert>
      ) : null}
      {logout.error ? (
        <Alert tone="error" className="mb-5">
          {commonErrorMessage(logout.error) ?? 'No pudimos cerrar la sesión. Intentá de nuevo.'}
        </Alert>
      ) : null}

      {!enrolled && !enrollment ? (
        <div className="space-y-5">
          <ol className="list-decimal space-y-2 pl-5 text-sm leading-relaxed text-muted">
            <li>Instalá una app autenticadora en tu teléfono (Google Authenticator, Microsoft Authenticator, Authy u otra compatible).</li>
            <li>Escaneá el código QR que vamos a mostrarte.</li>
            <li>Ingresá el código de 6 dígitos que genera la app.</li>
          </ol>
          {enroll.error ? <Alert tone="error">{commonErrorMessage(enroll.error) ?? 'No pudimos iniciar la configuración. Intentá de nuevo.'}</Alert> : null}
          <Button variant="dark" block onClick={() => enroll.mutate()} disabled={enroll.isPending} aria-busy={enroll.isPending}>
            {enroll.isPending ? 'Generando…' : 'Empezar configuración'}
          </Button>
        </div>
      ) : null}

      {!enrolled && enrollment ? (
        <div className="mb-6 space-y-4">
          <EnrollmentQr uri={enrollment.otpauthUri} />
          <div className="rounded-lg bg-paper p-4 text-sm">
            <p className="font-semibold">¿No podés escanear el código?</p>
            <p className="mt-1 text-muted">Ingresá esta clave manualmente en tu app:</p>
            <p className="mt-2 break-all font-mono text-base font-bold tracking-wider" data-testid="mfa-secret">
              {groupSecret(enrollment.secretoBase32)}
            </p>
            <a className="mt-2 inline-block font-semibold text-brand-800 underline" href={enrollment.otpauthUri}>
              Abrir en mi app autenticadora
            </a>
          </div>
          <Alert tone="info">Guardá esta clave solo en tu app. Por seguridad, no se vuelve a mostrar.</Alert>
        </div>
      ) : null}

      {enrolled || enrollment ? (
        <MfaCodeForm
          submitLabel={enrolled ? 'Verificar e ingresar' : 'Confirmar y activar'}
          pendingLabel="Verificando…"
          pending={codeMutation.isPending}
          errorMessage={codeMutation.error ? mfaErrorMessage(codeMutation.error) : null}
          resetSignal={resetSignal}
          onSubmit={onCode}
        />
      ) : null}

      {enrolled ? (
        <p className="mt-5 text-sm text-muted">
          ¿Perdiste el acceso a tu app? Pedile a otro administrador que reinicie tu verificación.
        </p>
      ) : null}
    </AuthShell>
  );
}
