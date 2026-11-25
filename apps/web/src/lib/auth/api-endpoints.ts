/**
 * Endpoints and grants of the authentication of the Java API (`apps/api`, PCAR-4,
 * docs/adr/0001-api-authentication.md). One place, so the DAL never spells a path twice.
 */
export const AUTH_ENDPOINTS = {
  /** POST: a token pair, by the password grant (sign in) or the refresh_token grant. */
  tokens: '/api/auth/tokens',
  /** POST: revokes a refresh token (sign out); 204. */
  revocations: '/api/auth/revocations',
  /** GET, PUT: the account of the access token. */
  me: '/api/users/me',
  /** PUT: changes the password; 204, and every refresh token of the account is revoked. */
  password: '/api/users/me/password',
} as const;

/** The `grantType` values of `POST /api/auth/tokens`. */
export const GRANT_TYPE = {
  password: 'password',
  refreshToken: 'refresh_token',
} as const;

/** Permission modules of the API (`app.permissions.module`, V20). */
export const PERMISSION_MODULE = {
  product: 'product',
} as const;

/** Method and path of the API permissions the UI checks, as `GET /api/users/me` lists them. */
export const API_PERMISSION = {
  productDelete: { method: 'DELETE', path: '/api/products/{id}' },
} as const;
