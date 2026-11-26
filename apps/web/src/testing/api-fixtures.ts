/**
 * Responses of the Java API (`apps/api`, PCAR-4) for unit tests, in the exact shape it sends, so
 * every test of the DAL checks the mapping against the real contract. `API_BASE` is the
 * `API_URL` that Vitest sets (`vitest.config.ts`).
 */
export const API_BASE = 'https://api.example.test';

export const ADMIN_ID = '0193c1f4-7b9a-7a3c-8d2e-aa1c98dead10';

/** `GET /api/users/me` of an ADMIN: the permissions of V20, with method and path. */
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

/** `POST /api/auth/tokens` on success. */
export function apiTokenPair(accessToken = 'a.b.c', refreshToken = 'refresh-1') {
  return { accessToken, refreshToken, tokenType: 'Bearer', expiresIn: 900 };
}

/** One `ProductResponse`. */
export function apiProduct(
  overrides: Partial<{
    id: string;
    name: string;
    slug: string;
    price: number;
    description: string | null;
    createdAt: string;
  }> = {},
) {
  return {
    id: '0193c1f4-7b9a-7a3c-8d2e-aa1c98dead01',
    name: 'Postgres Guide',
    slug: 'postgres-guide',
    price: 29.99,
    description: null,
    createdAt: '2026-11-26T01:00:00Z',
    ...overrides,
  };
}

/** A `PagedResponse` of `GET /api/products`; `number` counts from 0. */
export function apiPage<T>(content: T[], page = { number: 0, size: 10, totalElements: 0 }) {
  const totalElements = page.totalElements || content.length;
  return {
    content,
    page: {
      number: page.number,
      size: page.size,
      totalElements,
      totalPages: Math.ceil(totalElements / page.size),
    },
  };
}
