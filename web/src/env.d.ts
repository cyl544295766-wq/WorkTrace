/// <reference types="vite/client" />

import 'vue-router';

declare global {
  interface ImportMetaEnv {
    readonly VITE_API_BASE: string;
  }

  interface ImportMeta {
    readonly env: ImportMetaEnv;
  }
}

declare module 'vue-router' {
  interface RouteMeta {
    title?: string;
    roles?: 'PUBLIC' | 'ANY' | 'USER' | 'ADMIN';
  }
}

export {};
