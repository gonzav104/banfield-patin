import { BrandMark, BrandName } from '@/components/brand';

/** Provisional shell: the real routes, session and screens arrive with the authentication commits. */
export function App() {
  return (
    <main className="flex min-h-dvh items-center justify-center gap-3 bg-brand-800 p-6">
      <BrandMark />
      <BrandName />
    </main>
  );
}
