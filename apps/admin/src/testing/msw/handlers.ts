import { http, HttpResponse } from 'msw';

import {
  ADMIN_PROFILE,
  apiPage,
  apiProduct,
  apiTokenPair,
  GADGET_ID,
  WIDGET_ID,
} from '@/testing/api-fixtures';

// Default happy-path API handlers, in the shape the Java API sends (`apps/api`, PCAR-4), so the
// api layer's boundary validation runs against the real contract. Wildcard origins (`*/path`)
// match regardless of the configured base URL. Tests override per-case via `server.use(...)`.
const PRODUCTS = [
  apiProduct({ id: WIDGET_ID, name: 'Test Widget', slug: 'test-widget', price: 19.99 }),
  apiProduct({ id: GADGET_ID, name: 'Test Gadget', slug: 'test-gadget', price: 45 }),
];

export const handlers = [
  // GET /products?name=… filters by name, ignoring case, as the API does.
  http.get('*/products', ({ request }) => {
    const name = new URL(request.url).searchParams.get('name')?.toLowerCase() ?? '';
    return HttpResponse.json(
      apiPage(PRODUCTS.filter((product) => product.name.toLowerCase().includes(name))),
    );
  }),
  http.get('*/products/:id', ({ params }) =>
    HttpResponse.json(apiProduct({ id: String(params['id']), name: 'Test Widget' })),
  ),
  http.get('*/users/me', () => HttpResponse.json(ADMIN_PROFILE)),
  http.post('*/auth/tokens', () => HttpResponse.json(apiTokenPair('test-access', 'test-refresh'))),
  http.post('*/auth/revocations', () => new HttpResponse(null, { status: 204 })),
  http.post('*/products', async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    return HttpResponse.json(apiProduct({ id: 'created-id', ...body }), { status: 201 });
  }),
  http.put('*/products/:id', async ({ params, request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    return HttpResponse.json(apiProduct({ id: String(params['id']), ...body }));
  }),
  http.delete('*/products/:id', () => new HttpResponse(null, { status: 204 })),
];
