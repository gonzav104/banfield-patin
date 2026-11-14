import { useState, type FormEvent } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { ApiError } from '@/lib/api-error';
import { NAME_MAX_LENGTH } from './api';
import { useUpdateFamily } from './hooks';
import { classifyWriteFailure, detailErrorMessage, STALE_EDIT_TEXT, UNCERTAIN_EDIT_TEXT, writeErrorMessage, type WriteFailure } from './messages';
import { familyNameSchema, type FamilyDetail } from './schemas';
import { useUnsavedChangesGuard } from './useUnsavedChangesGuard';

type Phase = 'editing' | 'conflict' | 'uncertain' | 'reviewing';

interface FamilyNameEditorProps {
  /** The CURRENT server data (it follows reloads); the revision sent is the one captured when the edit started. */
  family: FamilyDetail;
  /** Reloads the detail on request. Resolves to the error, or null when the current data arrived. */
  reload: () => Promise<ApiError | null>;
  onClose: (message?: string) => void;
}

/**
 * Edits the reference name. The draft lives in this component's memory only. The revision to send (`baseVersion`) is
 * taken from the detail when the edit starts and changes ONLY when the administrator, after seeing the current data,
 * explicitly chooses to continue on top of them: it is never refreshed silently and there is no forced save.
 */
export function FamilyNameEditor({ family, reload, onClose }: FamilyNameEditorProps) {
  const update = useUpdateFamily();
  const [base, setBase] = useState({ version: family.version, name: family.nombreReferencia });
  const [draft, setDraft] = useState(family.nombreReferencia);
  const [phase, setPhase] = useState<Phase>('editing');
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [failure, setFailure] = useState<{ kind: WriteFailure; error: ApiError } | null>(null);
  const [checking, setChecking] = useState(false);
  const [reloadError, setReloadError] = useState<string | null>(null);

  const dirty = draft.trim() !== base.name;
  const guard = useUnsavedChangesGuard(dirty && !update.isPending);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (update.isPending || phase !== 'editing' || !dirty) return; // a second submit while one is in flight must never write twice
    const parsed = familyNameSchema.safeParse(draft);
    if (!parsed.success) {
      setFieldError(parsed.error.issues[0]?.message ?? 'Revisá el nombre.');
      return;
    }
    setFieldError(null);
    setFailure(null);
    update.mutate(
      { id: family.id, nombreReferencia: parsed.data, version: base.version },
      {
        onSuccess: () => onClose('Nombre actualizado.'),
        onError: (error) => {
          const apiError = ApiError.from(error);
          const kind = classifyWriteFailure(apiError);
          setFailure({ kind, error: apiError });
          if (kind === 'stale') setPhase('conflict');
          else if (kind === 'uncertain') setPhase('uncertain');
        },
      },
    );
  }

  async function checkCurrentData() {
    setChecking(true);
    setReloadError(null);
    const error = await reload();
    setChecking(false);
    if (error) setReloadError(detailErrorMessage(error));
    else setPhase('reviewing');
  }

  const saving = update.isPending;
  const alreadySaved = phase === 'reviewing' && family.nombreReferencia === draft.trim();

  return (
    <form noValidate aria-label="Editar nombre de la familia" onSubmit={submit} className="space-y-4 rounded-2xl border border-line bg-white p-5">
      <h2 className="text-lg font-bold">Editar nombre</h2>
      {guard.panel}

      {failure && (phase === 'editing' || phase === 'conflict' || phase === 'uncertain') ? (
        <Alert tone="error">
          {failure.kind === 'stale'
            ? STALE_EDIT_TEXT
            : failure.kind === 'uncertain'
              ? UNCERTAIN_EDIT_TEXT
              : writeErrorMessage(failure.kind, failure.error, 'name')}
        </Alert>
      ) : null}
      {reloadError ? <Alert tone="error">{reloadError}</Alert> : null}

      <Field
        label="Nombre de referencia"
        required
        maxLength={NAME_MAX_LENGTH}
        autoComplete="off"
        hint="Cómo la identificás vos, por ejemplo el apellido."
        error={fieldError ?? undefined}
        value={draft}
        onChange={(event) => {
          setDraft(event.target.value);
          setFieldError(null);
        }}
      />

      {phase === 'conflict' || phase === 'uncertain' ? (
        <Button variant="outline" disabled={checking} onClick={() => void checkCurrentData()}>
          {checking ? 'Cargando…' : phase === 'conflict' ? 'Ver datos vigentes' : 'Verificar datos vigentes'}
        </Button>
      ) : null}

      {phase === 'reviewing' ? (
        <div role="group" aria-label="Datos vigentes" className="space-y-3 rounded-lg border border-line bg-paper p-4 text-sm">
          {alreadySaved ? (
            <>
              <p>El cambio ya figura guardado.</p>
              <Button onClick={() => onClose('Nombre actualizado.')}>Cerrar</Button>
            </>
          ) : (
            <>
              <p>Estos son los datos vigentes. Tu borrador no se guardó ni se mezcló con ellos.</p>
              <dl className="space-y-1">
                <div className="flex flex-wrap gap-1">
                  <dt className="font-semibold">Nombre vigente:</dt>
                  <dd className="break-words">{family.nombreReferencia}</dd>
                </div>
                <div className="flex flex-wrap gap-1">
                  <dt className="font-semibold">Tu borrador:</dt>
                  <dd className="break-words">{draft.trim()}</dd>
                </div>
              </dl>
              <div className="flex flex-col gap-2 sm:flex-row">
                <Button
                  variant="outline"
                  onClick={() => {
                    // The administrator has seen the current data and chooses to build on them: only now does the revision change.
                    setBase({ version: family.version, name: family.nombreReferencia });
                    setFailure(null);
                    setPhase('editing');
                  }}
                >
                  Seguir editando mi borrador
                </Button>
                <Button variant="outline" onClick={() => onClose()}>
                  Descartar mi borrador
                </Button>
              </div>
            </>
          )}
        </div>
      ) : null}

      {phase === 'editing' ? (
        <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
          <Button variant="outline" disabled={saving} onClick={() => guard.ask(() => onClose())}>
            Cancelar
          </Button>
          <Button type="submit" disabled={saving || !dirty} aria-busy={saving}>
            {saving ? 'Guardando…' : 'Guardar cambios'}
          </Button>
        </div>
      ) : (
        <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
          <Button variant="outline" disabled={checking} onClick={() => guard.ask(() => onClose())}>
            Cancelar
          </Button>
          <Button type="submit" disabled>
            Guardar cambios
          </Button>
        </div>
      )}
    </form>
  );
}
