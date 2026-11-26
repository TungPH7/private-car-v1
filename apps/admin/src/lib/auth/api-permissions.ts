// Permissions of the Java API (`app.permissions`, V20 of `apps/api`) the UI checks: `GET /users/me`
// lists each one with its module, method and path. `<Can>` and `<RequireModule>` compare them.

/** Permission modules. */
export const PERMISSION_MODULE = {
  product: 'product',
} as const;

/** Method and path of each product permission. */
export const API_PERMISSION = {
  productCreate: { method: 'POST', path: '/api/products' },
  productUpdate: { method: 'PUT', path: '/api/products/{id}' },
  productDelete: { method: 'DELETE', path: '/api/products/{id}' },
} as const;
