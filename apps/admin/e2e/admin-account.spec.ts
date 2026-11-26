import { writeFileSync } from 'node:fs';

import { expect, test } from '@playwright/test';

import { adminAccount } from './support/admin-account';

// Which admin account the E2E signs in with, by where the admin under test runs (PCAR-6, round 2).
// Pure logic: no page, no server. Each test writes its own dotenv file standing in for apps/api/.env.

const LOCAL_ADMIN = 'http://localhost:5273';
const DEV_ADMIN = 'https://private-car-v1-admin-dev.ao.t2g.io.vn';

function bootstrapDotenv(file: string): string {
  writeFileSync(
    file,
    [
      'DB_PORT=5432',
      'APP_AUTH_BOOTSTRAP_ADMIN_EMAIL="local-admin@example.test"',
      "APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD='local-secret'",
    ].join('\n'),
  );
  return file;
}

test.describe('admin account of the E2E', () => {
  test('on this machine, it signs in as the bootstrap admin of apps/api/.env', () => {
    const dotenvFile = bootstrapDotenv(test.info().outputPath('api.env'));

    const account = adminAccount(LOCAL_ADMIN, { env: {}, dotenvFile });

    expect(account).toEqual({
      ok: true,
      credentials: { email: 'local-admin@example.test', password: 'local-secret' },
    });
  });

  test('on this machine, it ignores E2E_ADMIN_* set for an environment', () => {
    const dotenvFile = bootstrapDotenv(test.info().outputPath('api.env'));
    const env = { E2E_ADMIN_EMAIL: 'dev-admin@example.test', E2E_ADMIN_PASSWORD: 'dev-secret' };

    const account = adminAccount(LOCAL_ADMIN, { env, dotenvFile });

    expect(account).toEqual({
      ok: true,
      credentials: { email: 'local-admin@example.test', password: 'local-secret' },
    });
  });

  test('on an environment, it signs in as E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD', () => {
    const dotenvFile = bootstrapDotenv(test.info().outputPath('api.env'));
    const env = {
      E2E_BASE_URL: 'https://private-car-v1-dev.ao.t2g.io.vn',
      E2E_ADMIN_URL: DEV_ADMIN,
      E2E_ADMIN_EMAIL: 'dev-admin@example.test',
      E2E_ADMIN_PASSWORD: 'dev-secret',
    };

    const account = adminAccount(DEV_ADMIN, { env, dotenvFile });

    expect(account).toEqual({
      ok: true,
      credentials: { email: 'dev-admin@example.test', password: 'dev-secret' },
    });
  });

  test('on an environment without E2E_ADMIN_*, it gives the reason to skip, never the local admin', () => {
    const dotenvFile = bootstrapDotenv(test.info().outputPath('api.env'));
    const env = {
      E2E_BASE_URL: 'https://private-car-v1-dev.ao.t2g.io.vn',
      E2E_ADMIN_URL: DEV_ADMIN,
    };

    const account = adminAccount(DEV_ADMIN, { env, dotenvFile });

    expect(account.ok).toBe(false);
    if (account.ok) return;
    expect(account.reason).toContain('E2E_ADMIN_EMAIL');
    expect(account.reason).toContain('E2E_ADMIN_PASSWORD');
  });

  test('a test tagged @local under /ao-qa runs on the admin started here, so it signs in as the bootstrap admin', () => {
    const dotenvFile = bootstrapDotenv(test.info().outputPath('api.env'));
    const env = {
      E2E_BASE_URL: 'https://private-car-v1-dev.ao.t2g.io.vn',
      E2E_ADMIN_URL: DEV_ADMIN,
      E2E_ADMIN_EMAIL: 'dev-admin@example.test',
      E2E_ADMIN_PASSWORD: 'dev-secret',
    };

    const account = adminAccount('http://127.0.0.1:5273', { env, dotenvFile });

    expect(account).toEqual({
      ok: true,
      credentials: { email: 'local-admin@example.test', password: 'local-secret' },
    });
  });

  test('on this machine without the bootstrap admin, it gives the reason to skip', () => {
    const dotenvFile = test.info().outputPath('missing.env');

    const account = adminAccount(LOCAL_ADMIN, { env: {}, dotenvFile });

    expect(account.ok).toBe(false);
    if (account.ok) return;
    expect(account.reason).toContain('APP_AUTH_BOOTSTRAP_ADMIN_');
  });

  test('without a base URL, it runs on an environment only when E2E_BASE_URL is set', () => {
    const dotenvFile = bootstrapDotenv(test.info().outputPath('api.env'));
    const admins = { E2E_ADMIN_EMAIL: 'dev-admin@example.test', E2E_ADMIN_PASSWORD: 'dev-secret' };

    const local = adminAccount(undefined, { env: admins, dotenvFile });
    const remote = adminAccount(undefined, {
      env: { ...admins, E2E_BASE_URL: 'https://private-car-v1-dev.ao.t2g.io.vn' },
      dotenvFile,
    });

    expect(local.ok && local.credentials.email).toBe('local-admin@example.test');
    expect(remote.ok && remote.credentials.email).toBe('dev-admin@example.test');
  });
});
