import 'server-only';

import { cacheLife, cacheTag } from 'next/cache';

import {
  mapToProductList,
  PRODUCT_ENDPOINTS,
  type ProductListResult,
  productListResponseSchema,
} from '~/features/product/model/product';
import {
  type ProductListQuery,
  productQueryToApiParams,
} from '~/features/product/model/product-query';
import { httpGet } from '~/lib/http/client';

/** Products per page — FIXED in the DAL (the client cannot change `size` through the URL). */
const PAGE_SIZE = 10;

/**
 * Product list DAL — BFF: calls `GET /api/products` of the Java API through the http client
 * (folder 11) with a VALIDATED query (folder 13). Product reads are public there: no token.
 *
 * - `use cache` (folder 10), cache key = **args** → each query (page, sort, search) is its own
 *   entry; `cacheLife('hours')` + `cacheTag('products')` → `updateTag('products')` (folder 12)
 *   refreshes them all.
 * - Returns `ProductListResult`: minimal DTOs + paging meta, the page counted from 1 as in the UI.
 */
export async function getProducts(query: ProductListQuery): Promise<ProductListResult> {
  'use cache';
  cacheLife('hours');
  cacheTag('products');

  const params = productQueryToApiParams(query, PAGE_SIZE);
  const response = await httpGet(
    `${PRODUCT_ENDPOINTS.list}?${params.toString()}`,
    productListResponseSchema,
  );
  return mapToProductList(response);
}
