import { cn } from '@/lib/utils';

/** Provisional mark (green shield + checkered strip) until the official logo asset is approved for the app. */
export function BrandMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 40 44" aria-hidden="true" className={cn('size-9 shrink-0', className)}>
      <path d="M20 1 38 7v15c0 10-7 17-18 21C9 39 2 32 2 22V7z" fill="#0a6535" stroke="#fff" strokeWidth="2" />
      <path d="M8 26h24v8H8z" fill="#fff" />
      <g fill="#0c100d">
        <rect x="8" y="26" width="4" height="4" />
        <rect x="16" y="26" width="4" height="4" />
        <rect x="24" y="26" width="4" height="4" />
        <rect x="12" y="30" width="4" height="4" />
        <rect x="20" y="30" width="4" height="4" />
        <rect x="28" y="30" width="4" height="4" />
      </g>
      <text x="20" y="21" textAnchor="middle" fontSize="13" fontWeight="800" fill="#fff" fontFamily="system-ui">
        BP
      </text>
    </svg>
  );
}

export function BrandName({ className, tone = 'light' }: { className?: string; tone?: 'light' | 'dark' }) {
  return (
    <span className={cn('leading-tight', className)}>
      <span className={cn('block text-base font-extrabold tracking-tight', tone === 'light' ? 'text-white' : 'text-ink')}>
        Banfield San Pedro
      </span>
      <span
        className={cn('block text-xs font-semibold uppercase tracking-widest', tone === 'light' ? 'text-brand-200' : 'text-brand-700')}
      >
        Patín Carrera
      </span>
    </span>
  );
}
