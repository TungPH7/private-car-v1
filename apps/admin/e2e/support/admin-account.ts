import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

// The admin account the E2E signs in with on the real Java API (PCAR-6), chosen by where the admin
// under test runs, which is the base URL of the test:
// - on an environment (/ao-qa on Development or Staging): `E2E_ADMIN_EMAIL` and `E2E_ADMIN_PASSWORD`,
//   the admin of that environment. Without them the test skips: the admin of this machine does not
//   exist there.
// - on this machine (an admin Playwright starts here, a test tagged @local included): always the
//   bootstrap admin of `apps/api/.env` (`APP_AUTH_BOOTSTRAP_ADMIN_*`), which the local API creates at
//   start, even when `E2E_ADMIN_*` is set for an environment.
// Without a base URL, `E2E_BASE_URL` tells an environment run, as the kit sets it under /ao-qa.
// Playwright runs from the repository root.

export interface AdminCredentials {
  email: string;
  password: string;
}

export type AdminAccount =
  | { ok: true; credentials: AdminCredentials }
  | { ok: false; reason: string };

export interface AdminAccountOptions {
  env?: Readonly<Record<string, string | undefined>>;
  /** The dotenv file of the local API. */
  dotenvFile?: string;
}

const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);

const NO_ENVIRONMENT_ADMIN =
  'No admin account of the environment: set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD to its admin';
const NO_LOCAL_ADMIN =
  'No local admin account: set APP_AUTH_BOOTSTRAP_ADMIN_EMAIL and APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD in apps/api/.env';

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

function runsOnEnvironment(
  baseURL: string | undefined,
  env: Readonly<Record<string, string | undefined>>,
): boolean {
  if (baseURL === undefined) return Boolean(env['E2E_BASE_URL']);
  return !LOCAL_HOSTS.has(new URL(baseURL).hostname);
}

function account(
  email: string | undefined,
  password: string | undefined,
  reason: string,
): AdminAccount {
  return email && password ? { ok: true, credentials: { email, password } } : { ok: false, reason };
}

/** The admin account for a test whose admin runs at `baseURL`, or why the test must skip. */
export function adminAccount(
  baseURL: string | undefined,
  {
    env = process.env,
    dotenvFile = join(process.cwd(), 'apps', 'api', '.env'),
  }: AdminAccountOptions = {},
): AdminAccount {
  if (runsOnEnvironment(baseURL, env)) {
    return account(env['E2E_ADMIN_EMAIL'], env['E2E_ADMIN_PASSWORD'], NO_ENVIRONMENT_ADMIN);
  }
  const dotenv = readDotenv(dotenvFile);
  return account(
    dotenv['APP_AUTH_BOOTSTRAP_ADMIN_EMAIL'],
    dotenv['APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD'],
    NO_LOCAL_ADMIN,
  );
}

/** A short id unique to one run, so parallel runs and lanes never meet each other's products. */
export function runId(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}
