import { z } from 'zod';

import { API_PERMISSION } from '~/lib/auth/api-endpoints';

/**
 * Model of the WRITE layer (Server Actions, folder 12) — apart from `product.ts` (the READ layer)
 * because a `'use server'` file may export async functions only; every schema, type and constant
 * lives in a plain file like this one, imported by the action and the client.
 */

/**
 * Input of `deleteProductAction`. It comes from `FormData` (client) → NOT trusted → validated in
 * the action: the id of a product of the Java API is a UUID.
 */
export const deleteProductInputSchema = z.object({
  productId: z.uuid(),
});

/**
 * The API permission that deletes a product: method and path as `GET /api/users/me` lists them
 * (V20 of the API: `product:delete`, held by ADMIN). `<Can>` hides the Delete button without it.
 */
export const DELETE_PRODUCT_PERMISSION = API_PERMISSION.productDelete;

/** Failure reason — enough for the client to show a localized message, no internal detail. */
export type DeleteFailureReason = 'validation' | 'auth' | 'unknown';

/**
 * State of `useActionState(deleteProductAction)`. A discriminated union, **serializable** (it
 * crosses the server↔client wire). The action RETURNS state (expected errors are not thrown
 * across the boundary); only `redirect()` and real errors throw.
 */
export type ProductActionState =
  | { status: 'idle' }
  | { status: 'success'; deletedId: string }
  | { status: 'error'; reason: DeleteFailureReason };

/** State of `useActionState(refreshProductsAction)` — when it refreshed (request time). */
export type RefreshState = { status: 'idle' } | { status: 'success'; refreshedAt: string };

/** Initial state of both actions — shared on the client. */
export const initialProductActionState: ProductActionState = { status: 'idle' };
export const initialRefreshState: RefreshState = { status: 'idle' };
