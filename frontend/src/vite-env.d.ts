/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Optional API base URL. Defaults to the same-origin `/api` (Vite proxy in dev, reverse proxy in production). */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
