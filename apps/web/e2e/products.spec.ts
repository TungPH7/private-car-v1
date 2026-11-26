import { expect, test } from '@playwright/test';

import { adminCredentials, createProducts, NO_ADMIN, runId } from './support/api';

// Products of the real Java API, rendered on the server (PCAR-5): the test makes its own products
// with a run id in their names, and opens the list filtered by that id.
test.describe('products (the Java API, rendered on the server)', () => {
  const admin = adminCredentials();
  const run = runId();
  let remove: (() => Promise<void>) | undefined;

  test.beforeAll(async () => {
    if (!admin) return;
    ({ remove } = await createProducts(admin, [
      { name: `E2E Widget ${run}`, slug: `e2e-widget-${run}`, price: 19.9 },
      { name: `E2E Gadget ${run}`, slug: `e2e-gadget-${run}`, price: 5 },
    ]));
  });

  test.afterAll(async () => {
    await remove?.();
  });

  test('lists the products of the API with their prices in the locale', async ({ page }) => {
    test.skip(!admin, NO_ADMIN); // reason: the test makes its data as the admin of the API, unknown on some environments
    await page.goto(`/en/products?name=${run}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Products' })).toBeVisible();
    await expect(page.getByText(`E2E Widget ${run}`)).toBeVisible();
    await expect(page.getByText(`E2E Gadget ${run}`)).toBeVisible();
    await expect(page.getByText('$19.90')).toBeVisible();
  });

  test('sorts by name through the filter form', async ({ page }) => {
    test.skip(!admin, NO_ADMIN); // reason: the test makes its data as the admin of the API, unknown on some environments
    await page.goto(`/en/products?name=${run}&sortBy=name&orderBy=asc`);
    const names = page.locator('li').filter({ hasText: run });
    await expect(names.first()).toContainText(`E2E Gadget ${run}`);
    await expect(names.nth(1)).toContainText(`E2E Widget ${run}`);
  });

  test('the count badge: client → Route Handler /api → DAL → the API', async ({ page }) => {
    await page.goto('/en/products');
    await expect(page.getByText(/Total products:\s*\d+/)).toBeVisible();
  });
});
