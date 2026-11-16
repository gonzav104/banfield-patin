import { Field } from '@/components/ui/field';
import { MAX_LENGTH, schoolToday, type Errors, type FieldName, type Values } from './athlete-form';

interface AthleteFieldsProps {
  values: Values;
  errors: Errors;
  onChange: (field: FieldName, value: string) => void;
}

/** The thirteen personal-data fields of a new athlete, grouped the way the detail screen groups them. */
export function AthleteFields({ values, errors, onChange }: AthleteFieldsProps) {
  const text = (field: FieldName, label: string, extra: { required?: boolean; hint?: string; type?: string; inputMode?: 'tel' | 'email' | 'numeric' } = {}) => (
    <Field
      label={label}
      required={extra.required}
      maxLength={MAX_LENGTH[field]}
      autoComplete="off"
      {...(extra.hint ? { hint: extra.hint } : {})}
      {...(extra.type ? { type: extra.type } : {})}
      {...(extra.inputMode ? { inputMode: extra.inputMode } : {})}
      value={values[field]}
      error={errors[field]}
      onChange={(event) => onChange(field, event.target.value)}
    />
  );
  return (
    <>
      <fieldset className="space-y-5">
        <legend className="mb-1 text-base font-bold">Identificación</legend>
        {text('dni', 'DNI', { required: true, hint: 'Entre 7 y 9 dígitos; podés escribirlo con puntos.', inputMode: 'numeric' })}
        <div className="grid gap-5 sm:grid-cols-2">
          {text('nombre', 'Nombre', { required: true })}
          {text('apellido', 'Apellido', { required: true })}
        </div>
        <div className="grid gap-5 sm:grid-cols-2">
          {text('cuil', 'CUIL (opcional)', { hint: 'Once dígitos; podés escribirlo con guiones.', inputMode: 'numeric' })}
          <Field
            label="Fecha de nacimiento (opcional)"
            type="date"
            max={schoolToday()}
            autoComplete="off"
            value={values.fechaNacimiento}
            error={errors.fechaNacimiento}
            onChange={(event) => onChange('fechaNacimiento', event.target.value)}
          />
        </div>
        {text('nacionalidad', 'Nacionalidad (opcional)')}
      </fieldset>
      <fieldset className="space-y-5">
        <legend className="mb-1 text-base font-bold">Contacto</legend>
        <div className="grid gap-5 sm:grid-cols-2">
          {text('telefonoContacto', 'Teléfono (opcional)', { type: 'tel', inputMode: 'tel' })}
          {text('emailFederativo', 'Correo federativo (opcional)', { type: 'email', inputMode: 'email' })}
        </div>
      </fieldset>
      <fieldset className="space-y-5">
        <legend className="mb-1 text-base font-bold">Domicilio</legend>
        {text('domicilio', 'Domicilio (opcional)')}
        {text('otrosDatosDomicilio', 'Otros datos del domicilio (opcional)', { hint: 'Por ejemplo: piso, departamento, entre calles.' })}
        <div className="grid gap-5 sm:grid-cols-3">
          {text('localidad', 'Localidad (opcional)')}
          {text('partido', 'Partido (opcional)')}
          {text('codigoPostal', 'Código postal (opcional)')}
        </div>
      </fieldset>
    </>
  );
}
