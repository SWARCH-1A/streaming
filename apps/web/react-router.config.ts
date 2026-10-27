import type { Config } from '@react-router/dev/config';

// SPEC-18: every route is rendered on the server and hydrated in the browser.
export default {
  appDirectory: 'src/app',
  buildDirectory: 'build',
  ssr: true,
} satisfies Config;
