import { defineWorkersConfig, readD1Migrations } from '@cloudflare/vitest-pool-workers/config';

export default defineWorkersConfig(async () => ({
  test: {
    poolOptions: {
      workers: {
        main: './src/index.ts',
        miniflare: {
          compatibilityDate: '2024-11-01',
          compatibilityFlags: ['nodejs_compat'],
          d1Databases: ['DB'],
          r2Buckets: ['MEDIA'],
          durableObjects: { REALTIME: 'RealtimeRoom' },
          bindings: {
            JWT_SECRET: 'local-tests-only-not-a-production-secret',
            PUBLIC_API_URL: 'https://example.test',
            TEST_MIGRATIONS: await readD1Migrations('./migrations'),
          },
        },
      },
    },
  },
}));
