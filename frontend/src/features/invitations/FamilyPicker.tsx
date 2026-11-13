import { useState } from 'react';
import { Alert } from '@/components/ui/alert';
import { Input } from '@/components/ui/field';
import { commonErrorMessage } from '@/features/auth/messages';
import { ApiError } from '@/lib/api-error';
import { useFamilySearch } from './hooks';
import { useDebouncedValue } from './useDebouncedValue';

interface FamilyPickerProps {
  value: string | undefined;
  onChange: (familyId: string) => void;
  error?: string | undefined;
}

/** Search-and-select for an existing family (GET /api/admin/familias?busqueda=). */
export function FamilyPicker({ value, onChange, error }: FamilyPickerProps) {
  const [search, setSearch] = useState('');
  const debounced = useDebouncedValue(search.trim(), 250);
  const families = useFamilySearch(debounced, true);
  const items = families.data?.contenido ?? [];

  return (
    <fieldset className="space-y-3" aria-describedby={error ? 'family-picker-error' : undefined}>
      <legend className="text-sm font-semibold">
        Familia <span aria-hidden="true" className="text-red-700">*</span>
      </legend>
      <Input
        type="search"
        aria-label="Buscar familia por nombre"
        placeholder="Buscar por nombre…"
        value={search}
        onChange={(event) => setSearch(event.target.value)}
        autoComplete="off"
      />
      {families.isError ? (
        <Alert tone="error">{commonErrorMessage(ApiError.from(families.error)) ?? 'No pudimos cargar las familias.'}</Alert>
      ) : families.isPending ? (
        <p role="status" className="text-sm text-muted">Buscando familias…</p>
      ) : items.length === 0 ? (
        <p role="status" className="text-sm text-muted">No encontramos familias con ese nombre.</p>
      ) : (
        <ul className="divide-y divide-line overflow-hidden rounded-lg border border-line bg-white">
          {items.map((family) => (
            <li key={family.id}>
              <label className="flex min-h-11 cursor-pointer items-center gap-3 px-3 py-2 text-sm has-[:checked]:bg-brand-50">
                <input
                  type="radio"
                  name="family"
                  className="size-4 accent-brand-700"
                  checked={value === family.id}
                  onChange={() => onChange(family.id)}
                />
                <span className="font-medium">{family.nombreReferencia}</span>
              </label>
            </li>
          ))}
        </ul>
      )}
      {error ? (
        <p id="family-picker-error" className="text-sm font-medium text-red-800">{error}</p>
      ) : null}
    </fieldset>
  );
}
