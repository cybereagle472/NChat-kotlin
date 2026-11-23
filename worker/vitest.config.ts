import { cloudflareTest, readD1Migrations } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';

export default defineConfig(async () => ({
  plugins: [cloudflareTest({
    main: './src/index.ts',
    miniflare: {
      compatibilityDate: '2024-11-01',
      compatibilityFlags: ['nodejs_compat'],
      d1Databases: { DB: 'nchat-db-test', DB_RESTORE: 'nchat-db-restore-test' },
      r2Buckets: { MEDIA: 'nchat-media-test', BACKUPS: 'nchat-backups-test' },
      durableObjects: { REALTIME: 'RealtimeRoom' },
      bindings: {
        JWT_SECRET: 'local-tests-only-not-a-production-secret',
        PUBLIC_API_URL: 'https://example.test',
        TEST_MIGRATIONS: await readD1Migrations('./migrations'),
      },
    },
  })],
  test: {
    exclude: ['test/realtime/**', 'node_modules/**'],
  },
}));
