import { defineWorkersConfig } from '@cloudflare/vitest-pool-workers/config';

// The Wrangler 3 pool's isolated-storage snapshotter fails when a Durable
// Object creates SQLite sidecar files. Keep this suite in a fresh, separate
// in-memory runtime per invocation; tests here must clean up their own state.
export default defineWorkersConfig({
  test: {
    include: ['test/realtime/**/*.test.ts'],
    poolOptions: {
      workers: {
        main: './src/index.ts',
        isolatedStorage: false,
        miniflare: {
          compatibilityDate: '2024-11-01',
          compatibilityFlags: ['nodejs_compat'],
          d1Databases: ['DB'],
          r2Buckets: ['MEDIA'],
          durableObjects: { REALTIME: 'RealtimeRoom' },
          bindings: { JWT_SECRET: 'local-tests-only-not-a-production-secret', PUBLIC_API_URL: 'https://example.test' },
        },
      },
    },
  },
});
