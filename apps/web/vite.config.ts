import { fileURLToPath, URL } from 'node:url';

import react from '@vitejs/plugin-react-swc';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  // Una lista explícita mantiene SWC también en el build de Vite 8.
  plugins: [react({ plugins: [] })],
  publicDir: false,
  resolve: { alias: { '@': fileURLToPath(new URL('.', import.meta.url)) } },
  server: { host: '127.0.0.1', port: 3000, strictPort: true },
  preview: { host: '127.0.0.1', port: 3000, strictPort: true },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
    coverage: {
      provider: 'v8',
      include: [
        'src/components/**/*.{ts,tsx}',
        'src/modules/**/*.{ts,tsx}',
        'src/shell/**/*.{ts,tsx}',
      ],
      exclude: ['**/mock/**', '**/entry.tsx', '**/*.types.ts', '**/design-system/**'],
      reporter: ['text', 'html'],
    },
  },
});
