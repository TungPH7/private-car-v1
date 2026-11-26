// Endpoints of the Java API (`apps/api`, PCAR-4, docs/adr/0001-api-authentication.md), relative to
// `env.apiUrl` (`/api`, which the dev server and the Caddy of the container proxy to the API). One
// place, so no module spells a path twice.
export const API_ENDPOINTS = {
  /** POST: a token pair, by the password grant (sign in) or the refresh_token grant. */
  tokens: '/auth/tokens',
  /** POST: revokes a refresh token (sign out) → 204. */
  revocations: '/auth/revocations',
  /** GET: the account of the access token, with its role and permissions. */
  me: '/users/me',
  /** GET (public, paged), POST (`product:create`). */
  products: '/products',
  /** GET (public), PUT (`product:update`), DELETE (`product:delete`) → 204. */
  product: (productId: string) => `/products/${encodeURIComponent(productId)}`,
} as const;

/** The `grantType` values of `POST /auth/tokens`. */
export const GRANT_TYPE = {
  password: 'password',
  refreshToken: 'refresh_token',
} as const;

/**
 * The header that carries the access token. On Development and Staging the Caddy of the VPS asks
 * for basic auth on every host, so the browser already uses `Authorization` for it; the API reads
 * the token from this header when `Authorization` carries no Bearer (AccessTokenResolver).
 */
export const ACCESS_TOKEN_HEADER = 'X-Access-Token';
