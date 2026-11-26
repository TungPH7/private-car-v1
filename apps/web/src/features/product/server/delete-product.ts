import 'server-only';

import { PRODUCT_ENDPOINTS } from '~/features/product/model/product';
import { authHeaders } from '~/lib/auth/auth-headers';
import { httpSendNoContent } from '~/lib/http/client';

/**
 * Write DAL — deletes one product through the Java API (BFF). `server-only`, so it never reaches
 * the client bundle.
 *
 * Contract: `DELETE /api/products/{id}` needs a token with `product:delete` → 204. Without a token
 * the API answers 401 (the action maps it to 'auth'); signed in without the permission, 403
 * (defense in depth, apart from the re-authorization of the action).
 *
 * ⚠️ No `use cache` here (a mutation, not a read): the Server Action calls `updateTag('products')`
 * after a successful delete. `authHeaders()` reads the httpOnly access token OUTSIDE use cache.
 */
export async function deleteProduct(productId: string): Promise<void> {
  await httpSendNoContent('DELETE', PRODUCT_ENDPOINTS.item(productId), {
    headers: await authHeaders(),
  });
}
