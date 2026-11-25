import { expect, test, type Page } from '@playwright/test';

import {
  adminCredentials,
  type AdminCredentials,
  createProducts,
  NO_ADMIN,
  runId,
} from './support/api';

// Sign-in, RBAC and sign-out against the real Java API (PCAR-5), with the admin account of the API.
async function signIn(page: Page, admin: AdminCredentials): Promise<void> {
  await page.goto('/en/login');
  await page.getByLabel('Email').fill(admin.email);
  await page.getByLabel('Password').fill(admin.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/en$/); // loginAction redirects to '/' of the current locale.
}

test.describe('auth: sign in, RBAC, sign out', () => {
  const admin = adminCredentials();
  const run = runId();
  let remove: (() => Promise<void>) | undefined;

  test.beforeAll(async () => {
    if (!admin) return;
    ({ remove } = await createProducts(admin, [
      { name: `E2E Kept ${run}`, slug: `e2e-kept-${run}`, price: 12 },
      { name: `E2E Deletable ${run}`, slug: `e2e-deletable-${run}`, price: 3 },
    ]));
  });

  test.afterAll(async () => {
    await remove?.();
  });

  test('a wrong password shows the credentials error and signs nobody in', async ({ page }) => {
    await page.goto('/en/login');
    await page.getByLabel('Email').fill('nobody@example.com');
    await page.getByLabel('Password').fill('not the password');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.locator('form').getByRole('alert')).toBeVisible();
    await expect(page).toHaveURL(/\/en\/login$/);
  });

  test('signing in shows the signed-in state with the role (ADMIN)', async ({ page }) => {
    test.skip(!admin, NO_ADMIN); // reason: the test makes its data as the admin of the API, unknown on some environments
    if (!admin) return;
    await signIn(page, admin);
    await expect(page.getByText('Signed in (ADMIN)')).toBeVisible();
  });

  test('anonymous: no Delete button on /products (<Can> + RBAC on the server)', async ({
    page,
  }) => {
    test.skip(!admin, NO_ADMIN); // reason: the test makes its data as the admin of the API, unknown on some environments
    await page.goto(`/en/products?name=${run}`);
    await expect(page.getByText(`E2E Kept ${run}`)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Delete' })).toHaveCount(0);
  });

  test('signed in as ADMIN: the Delete button shows (product:delete of the API)', async ({
    page,
  }) => {
    test.skip(!admin, NO_ADMIN); // reason: the test makes its data as the admin of the API, unknown on some environments
    if (!admin) return;
    await signIn(page, admin);
    await page.goto(`/en/products?name=${run}`);
    const row = page.locator('li').filter({ hasText: `E2E Kept ${run}` });
    await expect(row.getByRole('button', { name: 'Delete' })).toBeVisible();
  });

  test('deleting a product: mutation → API → updateTag → server re-render', async ({ page }) => {
    test.skip(!admin, NO_ADMIN); // reason: the test makes its data as the admin of the API, unknown on some environments
    if (!admin) return;
    await signIn(page, admin);
    await page.goto(`/en/products?name=${run}`);
    const row = page.locator('li').filter({ hasText: `E2E Deletable ${run}` });
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: 'Delete' }).click();
    await expect(page.getByText(`E2E Deletable ${run}`)).toHaveCount(0, { timeout: 20_000 });
    await expect(page.getByText(`E2E Kept ${run}`)).toBeVisible();
  });

  test('signing out → back to "Sign in"', async ({ page }) => {
    test.skip(!admin, NO_ADMIN); // reason: the test makes its data as the admin of the API, unknown on some environments
    if (!admin) return;
    await signIn(page, admin);
    await expect(page.getByText('Signed in (ADMIN)')).toBeVisible();
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();
  });
});
