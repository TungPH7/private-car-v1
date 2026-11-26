import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { type APIRequestContext, request } from '@playwright/test';
import { parse } from 'yaml';

/**
 * E2E support for the real Java API (`apps/api`) that the root Playwright config starts: the
 * tests make their own data through it, so they pass on this machine, in a lane and on an
 * environment alike (PCAR-5). Playwright runs from the repository root.
 *
 * - The API: `E2E_API_URL` when the kit tests an environment (`/ao-qa`), else localhost on the
 *   port of `.ai-orchestrator/project.yaml`, moved by `AO_PORT_OFFSET` in a lane.
 * - The admin account, by where the tests run (round 2): on an environment (`E2E_BASE_URL`, as
 *   under `/ao-qa`), `E2E_ADMIN_EMAIL` and `E2E_ADMIN_PASSWORD`, the admin of that environment; on
 *   this machine, always the bootstrap admin of `apps/api/.env` (`APP_AUTH_BOOTSTRAP_ADMIN_*`),
 *   which the API Playwright starts creates, even when `E2E_ADMIN_*` are set for `/ao-qa`.
 * - The token goes in `X-Access-Token`: on an environment, `Authorization` carries its basic auth.
 */

interface ProjectConfig {
  apps?: Record<string, { port?: { default?: number } }>;
}

export interface AdminCredentials {
  email: string;
  password: string;
}

export interface CreatedProduct {
  id: string;
  name: string;
  slug: string;
  price: number;
}

const ROOT = process.cwd();
const OFFSET = Number(process.env.AO_PORT_OFFSET ?? 0) || 0;

function localApiUrl(): string {
  const config = parse(
    readFileSync(join(ROOT, '.ai-orchestrator', 'project.yaml'), 'utf8'),
  ) as ProjectConfig;
  const port = config.apps?.api?.port?.default;
  if (port === undefined) throw new Error('project.yaml names no port for the app api');
  return `http://localhost:${port + OFFSET}`;
}

export const API_URL = process.env.E2E_API_URL ?? localApiUrl();

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

type Env = Readonly<Record<string, string | undefined>>;

/** Whether the tests run on an environment (`/ao-qa` sets `E2E_BASE_URL`), not on this machine. */
function onEnvironment(env: Env): boolean {
  return Boolean(env.E2E_BASE_URL);
}

/**
 * The admin account the tests sign in with, or null when none is known: on an environment, both
 * `E2E_ADMIN_*`; on this machine, the bootstrap admin of `dotenv` (`apps/api/.env`) only, since
 * `E2E_ADMIN_*` name the admin of an environment, whom the API started here does not know.
 */
export function adminCredentialsFrom(
  env: Env,
  dotenv: Readonly<Record<string, string>>,
): AdminCredentials | null {
  const [email, password] = onEnvironment(env)
    ? [env.E2E_ADMIN_EMAIL, env.E2E_ADMIN_PASSWORD]
    : [dotenv.APP_AUTH_BOOTSTRAP_ADMIN_EMAIL, dotenv.APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD];
  return email && password ? { email, password } : null;
}

/** The admin account of this run, from the environment variables and `apps/api/.env`. */
export function adminCredentials(): AdminCredentials | null {
  const dotenv = onEnvironment(process.env) ? {} : readDotenv(join(ROOT, 'apps', 'api', '.env'));
  return adminCredentialsFrom(process.env, dotenv);
}

/** Why a test that needs the admin account is skipped, naming what to set where the tests run. */
export function noAdminReason(env: Env): string {
  return onEnvironment(env)
    ? 'No admin account: set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD to the admin of the environment under test'
    : 'No admin account: set APP_AUTH_BOOTSTRAP_ADMIN_EMAIL and APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD in apps/api/.env';
}

/** Why a test that needs the admin account is skipped, for `test.skip`. */
export const NO_ADMIN = noAdminReason(process.env);

/** A request context for the API, with the basic auth of an environment when there is one. */
async function apiContext(): Promise<APIRequestContext> {
  const user = process.env.E2E_HTTP_USER;
  const password = process.env.E2E_HTTP_PASSWORD;
  return request.newContext({
    baseURL: API_URL,
    ...(user && password
      ? { httpCredentials: { username: user, password, origin: new URL(API_URL).origin } }
      : {}),
  });
}

/** A short id unique to one test run, so parallel runs and lanes never meet each other's data. */
export function runId(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

/** Products created as the admin; `remove()` deletes them, ignoring those a test deleted already. */
export async function createProducts(
  admin: AdminCredentials,
  products: { name: string; slug: string; price: number }[],
): Promise<{ created: CreatedProduct[]; remove: () => Promise<void> }> {
  const api = await apiContext();
  const signIn = await api.post('/api/auth/tokens', {
    data: { grantType: 'password', email: admin.email, password: admin.password },
  });
  if (!signIn.ok()) throw new Error(`the admin could not sign in to the API: ${signIn.status()}`);
  const { accessToken } = (await signIn.json()) as { accessToken: string };
  const headers = { 'X-Access-Token': accessToken };

  const created: CreatedProduct[] = [];
  for (const product of products) {
    const response = await api.post('/api/products', { headers, data: product });
    if (response.status() !== 201) {
      throw new Error(`POST /api/products answered ${response.status()}`);
    }
    created.push((await response.json()) as CreatedProduct);
  }

  return {
    created,
    remove: async () => {
      for (const product of created) {
        await api.delete(`/api/products/${product.id}`, { headers });
      }
      await api.dispose();
    },
  };
}
