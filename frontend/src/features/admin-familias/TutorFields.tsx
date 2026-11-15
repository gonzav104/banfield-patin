import { Field } from '@/components/ui/field';
import { type Errors, type FieldName, type Values } from './tutor-form';

interface TutorFieldsProps {
  values: Values;
  errors: Errors;
  onChange: (field: FieldName, value: string) => void;
}

/** The six personal-data fields of a tutor, identical in the new-tutor and the edit-tutor forms. */
export function TutorFields({ values, errors, onChange }: TutorFieldsProps) {
  return (
    <>
      <div className="grid gap-5 sm:grid-cols-2">
        <Field label="Nombre" required maxLength={100} autoComplete="off" value={values.nombre} error={errors.nombre} onChange={(e) => onChange('nombre', e.target.value)} />
        <Field label="Apellido" required maxLength={100} autoComplete="off" value={values.apellido} error={errors.apellido} onChange={(e) => onChange('apellido', e.target.value)} />
      </div>
      <Field
        label="DNI (opcional)"
        maxLength={20}
        autoComplete="off"
        hint="Entre 7 y 9 dígitos; podés escribirlo con puntos."
        value={values.dni}
        error={errors.dni}
        onChange={(e) => onChange('dni', e.target.value)}
      />
      <div className="grid gap-5 sm:grid-cols-2">
        <Field label="Teléfono (opcional)" type="tel" maxLength={40} autoComplete="off" value={values.telefono} error={errors.telefono} onChange={(e) => onChange('telefono', e.target.value)} />
        <Field label="Correo electrónico (opcional)" type="email" inputMode="email" maxLength={180} autoComplete="off" value={values.correo} error={errors.correo} onChange={(e) => onChange('correo', e.target.value)} />
      </div>
      <Field
        label="Parentesco (opcional)"
        maxLength={40}
        autoComplete="off"
        hint="Por ejemplo: madre, padre, tutor legal."
        value={values.parentesco}
        error={errors.parentesco}
        onChange={(e) => onChange('parentesco', e.target.value)}
      />
    </>
  );
}
