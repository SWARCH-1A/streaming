import { fileURLToPath, URL } from 'node:url';

import react from '@vitejs/plugin-react-swc';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  // Una lista explícita mantiene SWC también en el build de Vite 8.
  plugins: [
    react({ plugins: [] }),
    {
      name: 'same-origin-development-api',
      configureServer(server) {
        server.middlewares.use((req, res, next) => {
          const path = (req.url ?? '').split('?')[0] ?? '';
          const privatePath = /^\/(internal|actuator|health)(\/|$)/.test(path);
          if (
            privatePath ||
            (process.env.VITE_P1_PROXY !== '1' && /^\/(api|realtime|hls)(\/|$)/.test(path))
          ) {
            res.statusCode = privatePath ? 404 : 503;
            res.setHeader('Content-Type', 'application/json');
            res.end(
              JSON.stringify({
                code: privatePath ? 'NOT_FOUND' : 'UPSTREAM_NOT_CONFIGURED',
                message: 'Usa el perfil integrado o configura VITE_P1_PROXY=1.',
                requestId: 'vite-dev',
              }),
            );
            return;
          }
          next();
        });
      },
    },
  ],
  publicDir: false,
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('.', import.meta.url)),
      '@contracts': fileURLToPath(new URL('../../contracts/generated', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 3000,
    strictPort: true,
    ...(process.env.VITE_P1_PROXY === '1'
      ? {
          proxy: {
            '^/api/channels/[^/]+/streams$': {
              target: process.env.STREAMING_DEV_UPSTREAM ?? 'http://127.0.0.1:8080',
            },
            '/api/streams/': {
              target: process.env.STREAMING_DEV_UPSTREAM ?? 'http://127.0.0.1:8080',
            },
            '/api/chat/': { target: process.env.CHAT_DEV_UPSTREAM ?? 'http://127.0.0.1:8085' },
            '/realtime/chat/': {
              target: process.env.CHAT_DEV_UPSTREAM ?? 'http://127.0.0.1:8085',
              ws: true,
            },
            '/hls/': { target: process.env.HLS_DEV_UPSTREAM ?? 'http://127.0.0.1:8888' },
            '/api/': { target: process.env.CORE_DEV_UPSTREAM ?? 'http://127.0.0.1:8081' },
          },
        }
      : {}),
  },
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
