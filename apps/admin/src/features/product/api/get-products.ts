import { keepPreviousData, queryOptions, useQuery } from '@tanstack/react-query';

import { productKeys } from '@/features/product/api/query-keys';
import { type ProductPage, productPageSchema } from '@/features/product/model/product';
import { api } from '@/lib/api/api';
import { API_ENDPOINTS } from '@/lib/api/endpoints';

/** The largest page the API serves (`spring.data.web.pageable.max-page-size`). */
export const PRODUCT_LIST_SIZE = 100;

/** Newest first, as the API orders by default. */
const PRODUCT_LIST_SORT = 'createdAt,desc';

// Transport: fetch + validate at the boundary (folder 07). The API pages and filters: `name`
// keeps the products whose name contains it, ignoring case, over every product, not only the
// ones already loaded.
export function getProducts(search: string): Promise<ProductPage> {
  const name = search.trim();
  return api.get(API_ENDPOINTS.products, {
    params: {
      page: 0,
      size: PRODUCT_LIST_SIZE,
      sort: PRODUCT_LIST_SORT,
      ...(name === '' ? {} : { name }),
    },
    schema: productPageSchema,
  });
}

export function productsQueryOptions(search: string) {
  return queryOptions({
    queryKey: productKeys.list(search.trim()),
    queryFn: () => getProducts(search),
    // Keep the last list on screen while the next search loads (no flash of "loading").
    placeholderData: keepPreviousData,
  });
}

export function useProducts(search: string) {
  return useQuery(productsQueryOptions(search));
}
