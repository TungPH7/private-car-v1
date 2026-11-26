import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { ProductEditForm } from '@/features/product/components/product-edit-form';
import { apiProduct } from '@/testing/api-fixtures';
import { renderWithProviders, screen, waitFor } from '@/testing/render';
import { server } from '@/testing/msw/server';

const product = {
  id: 'p-001',
  name: 'Old Name',
  slug: 'old-name',
  price: 10,
  description: null,
};

describe('ProductEditForm', () => {
  it('pre-fills the product and submits an update (MSW PUT)', async () => {
    let putBody: Record<string, unknown> | null = null;
    server.use(
      http.put('*/products/:id', async ({ request }) => {
        putBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(apiProduct({ id: 'p-001', ...putBody }));
      }),
    );

    const { user } = renderWithProviders(<ProductEditForm product={product} />);

    const name = screen.getByLabelText('Name');
    expect(name).toHaveValue('Old Name'); // pre-filled from the product

    await user.clear(name);
    await user.type(name, 'New Name');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => {
      expect(putBody).not.toBeNull();
    });
    // The body of UpdateProductRequest: an empty description is sent as null.
    expect(putBody).toEqual({ name: 'New Name', slug: 'old-name', price: 10, description: null });
  });

  it('shows a validation error and does not submit when price is negative', async () => {
    let putCalled = false;
    server.use(
      http.put('*/products/:id', () => {
        putCalled = true;
        return HttpResponse.json(apiProduct({ id: 'p-001' }));
      }),
    );

    const { user } = renderWithProviders(<ProductEditForm product={product} />);

    const price = screen.getByLabelText('Price (USD)');
    await user.clear(price);
    await user.type(price, '-5');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument(); // zod nonnegative
    expect(putCalled).toBe(false);
  });

  it('shows an error when the update request fails', async () => {
    // 400 (not 5xx) so the api layer does not retry → fails fast.
    server.use(
      http.put('*/products/:id', () =>
        HttpResponse.json({ message: 'Bad request' }, { status: 400 }),
      ),
    );

    const { user } = renderWithProviders(<ProductEditForm product={product} />);
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});
