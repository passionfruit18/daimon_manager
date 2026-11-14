/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL of the local Quarkus daemon. Defaults to http://127.0.0.1:7777. */
  readonly VITE_DAIMON_URL?: string;
  /** Set to "true" to always use the in-browser simulation. */
  readonly VITE_DAIMON_MOCK?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
