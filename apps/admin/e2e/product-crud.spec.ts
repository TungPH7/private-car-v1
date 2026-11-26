import { expect, test, type Page } from '@playwright/test';

import { adminCredentials, type AdminCredentials, NO_ADMIN, runId } from './support/admin-account';

// Full product CRUD end to end on the REAL Java API (PCAR-6): the Playwright config of the kit
// starts web, admin, api and Postgres, and the admin dev server proxies /api to the API. The test
// signs in with the admin account of the API and goes through the UI only: RBAC (module and
// permissions of GET /users/me), forms with the rules of the API, TanStack Query invalidation.
// Its product carries a run id in its name and slug, so parallel runs never meet.

async function signIn(page: Page, admin: AdminCredentials): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Email').fill(admin.email);
  await page.getByLabel('Password').fill(admin.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/$/); // LoginForm navigates home on success.
}

test('Product CRUD end-to-end on the API (create → read → update → delete)', async ({ page }) => {
  const admin = adminCredentials();
  test.skip(!admin, NO_ADMIN); // reason: the test signs in as the admin of the API, unknown on some environments
  if (!admin) return;
  const run = runId();
  const name = `E2E Gadget ${run}`;

  await signIn(page, admin);

  // CREATE — through the RBAC-gated admin form (module product).
  await page.goto('/admin');
  await page.getByLabel('Name').fill(name);
  await page.getByLabel('Slug').fill(`e2e-gadget-${run}`);
  await page.getByLabel('Price (USD)').fill('25');
  await page.getByRole('button', { name: 'Create product' }).click();
  await expect(page.getByText('Product created.')).toBeVisible();

  // READ — the API finds it by name.
  await page.goto(`/products?q=${run}`);
  await expect(page.getByRole('link', { name })).toBeVisible();

  // UPDATE — open the detail, edit the price, see the new value.
  await page.getByRole('link', { name }).click();
  await expect(page.getByText('Price: $25.00')).toBeVisible();
  await page.getByRole('link', { name: 'Edit' }).click();
  await page.getByLabel('Price (USD)').fill('99.99');
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.getByText('Price: $99.99')).toBeVisible();

  // DELETE — confirm, land back on the list without the product.
  await page.getByRole('button', { name: 'Delete' }).click();
  await page.getByRole('button', { name: 'Yes, delete' }).click();
  await expect(page).toHaveURL(/\/products$/);
  await page.goto(`/products?q=${run}`);
  await expect(page.getByText('No products yet.')).toBeVisible();
  await expect(page.getByRole('link', { name })).toHaveCount(0);
});

test('a signed-out visitor is sent to sign in from the admin page', async ({ page }) => {
  await page.goto('/admin');
  await expect(page).toHaveURL(/\/login/);
});
