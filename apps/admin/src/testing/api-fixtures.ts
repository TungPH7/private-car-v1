// Responses of the Java API (`apps/api`, PCAR-4) for unit tests, in the exact shape it sends.

export const ADMIN_ID = '0193c1f4-7b9a-7a3c-8d2e-aa1c98dead10';
export const WIDGET_ID = '0193c1f4-7b9a-7a3c-8d2e-aa1c98dead01';
export const GADGET_ID = '0193c1f4-7b9a-7a3c-8d2e-aa1c98dead02';

/** `GET /users/me` of an ADMIN: the product permissions of V20, with method and path. */
export const ADMIN_PROFILE = {
  id: ADMIN_ID,
  email: 'admin@example.com',
  name: 'Admin',
  phoneNumber: null,
  avatar: null,
  role: {
    name: 'ADMIN',
    permissions: [
      { name: 'product:create', module: 'product', method: 'POST', path: '/api/products' },
      { name: 'product:delete', module: 'product', method: 'DELETE', path: '/api/products/{id}' },
      { name: 'product:update', module: 'product', method: 'PUT', path: '/api/products/{id}' },
    ],
  },
};

/** `GET /users/me` of a CUSTOMER: no permission. */
export const CUSTOMER_PROFILE = {
  ...ADMIN_PROFILE,
  id: '0193c1f4-7b9a-7a3c-8d2e-aa1c98dead11',
  email: 'customer@example.com',
  name: 'Customer',
  role: { name: 'CUSTOMER', permissions: [] },
};

/** `POST /auth/tokens` on success. */
export function apiTokenPair(accessToken = 'a.b.c', refreshToken = 'refresh-1') {
  return { accessToken, refreshToken, tokenType: 'Bearer', expiresIn: 900 };
}

/** One `ProductResponse`. */
export function apiProduct(overrides: Record<string, unknown> = {}) {
  return {
    id: WIDGET_ID,
    name: 'Test Widget',
    slug: 'test-widget',
    price: 19.99,
    description: null,
    createdAt: '2026-11-26T01:00:00Z',
    ...overrides,
  };
}

/** A `PagedResponse` of `GET /products`, every item on page 0. */
export function apiPage<T>(content: T[], size = 100) {
  return {
    content,
    page: {
      number: 0,
      size,
      totalElements: content.length,
      totalPages: content.length === 0 ? 0 : Math.ceil(content.length / size),
    },
  };
}
