import { expect, test } from '@playwright/test';

import { adminCredentialsFrom, noAdminReason } from './api';

// Which admin account the E2E signs in with, by where it runs (PCAR-5, round 2). Plain functions,
// no browser: on this machine the API is the one Playwright starts, whose admin is the bootstrap
// admin of apps/api/.env; on an environment (E2E_BASE_URL, as under /ao-qa) it is the admin of
// that environment, which only E2E_ADMIN_* can name.
test.describe('the admin account of the E2E', () => {
  const dotenv = {
    APP_AUTH_BOOTSTRAP_ADMIN_EMAIL: 'bootstrap@example.test',
    APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD: 'bootstrap-password',
  };
  const fromEnv = {
    E2E_ADMIN_EMAIL: 'qa-admin@example.test',
    E2E_ADMIN_PASSWORD: 'qa-admin-password',
  };

  test('on this machine, uses the bootstrap admin of apps/api/.env', () => {
    expect(adminCredentialsFrom({}, dotenv)).toEqual({
      email: 'bootstrap@example.test',
      password: 'bootstrap-password',
    });
  });

  test('on this machine, keeps the bootstrap admin even when E2E_ADMIN_* are set', () => {
    expect(adminCredentialsFrom(fromEnv, dotenv)).toEqual({
      email: 'bootstrap@example.test',
      password: 'bootstrap-password',
    });
  });

  test('on this machine, knows no admin when apps/api/.env has none', () => {
    expect(adminCredentialsFrom(fromEnv, {})).toBeNull();
  });

  test('on an environment, uses E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD', () => {
    const env = { ...fromEnv, E2E_BASE_URL: 'https://web.example.test' };
    expect(adminCredentialsFrom(env, dotenv)).toEqual({
      email: 'qa-admin@example.test',
      password: 'qa-admin-password',
    });
  });

  test('on an environment, knows no admin without both E2E_ADMIN_*, even with apps/api/.env', () => {
    const env = {
      E2E_BASE_URL: 'https://web.example.test',
      E2E_ADMIN_EMAIL: 'qa-admin@example.test',
    };
    expect(adminCredentialsFrom(env, dotenv)).toBeNull();
  });

  test('the skip reason names the variables of the place the tests run', () => {
    expect(noAdminReason({})).toContain('APP_AUTH_BOOTSTRAP_ADMIN_EMAIL');
    expect(noAdminReason({})).not.toContain('E2E_ADMIN_EMAIL');
    expect(noAdminReason({ E2E_BASE_URL: 'https://web.example.test' })).toContain(
      'E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD',
    );
  });
});
