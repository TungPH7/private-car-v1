import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

// The admin account the E2E signs in with on the real Java API (PCAR-6), as in apps/web/e2e:
// `E2E_ADMIN_EMAIL` and `E2E_ADMIN_PASSWORD` (an environment under /ao-qa), else the bootstrap admin
// of `apps/api/.env` (`APP_AUTH_BOOTSTRAP_ADMIN_*`), which the API creates at start. Playwright runs
// from the repository root.

export interface AdminCredentials {
  email: string;
  password: string;
}

/** `NAME=value` lines of a dotenv file; quotes around a value are dropped. */
function readDotenv(file: string): Record<string, string> {
  if (!existsSync(file)) return {};
  const values: Record<string, string> = {};
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const match = /^([A-Z0-9_]+)=(.*)$/.exec(line.trim());
    if (match?.[1] !== undefined && match[2] !== undefined) {
      values[match[1]] = match[2].replace(/^(['"])(.*)\1$/, '$2');
    }
  }
  return values;
}

export function adminCredentials(): AdminCredentials | null {
  const email = process.env['E2E_ADMIN_EMAIL'];
  const password = process.env['E2E_ADMIN_PASSWORD'];
  if (email && password) return { email, password };
  const dotenv = readDotenv(join(process.cwd(), 'apps', 'api', '.env'));
  const fromFile = {
    email: dotenv['APP_AUTH_BOOTSTRAP_ADMIN_EMAIL'],
    password: dotenv['APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD'],
  };
  return fromFile.email && fromFile.password
    ? { email: fromFile.email, password: fromFile.password }
    : null;
}

export const NO_ADMIN =
  'No admin account: set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD, or APP_AUTH_BOOTSTRAP_ADMIN_* in apps/api/.env';

/** A short id unique to one run, so parallel runs and lanes never meet each other's products. */
export function runId(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}
