import { useMutation, useQueryClient } from '@tanstack/react-query';

import { productKeys } from '@/features/product/api/query-keys';
import {
  type Product,
  type ProductInput,
  productSchema,
  toProductRequest,
} from '@/features/product/model/product';
import { api } from '@/lib/api/api';
import { API_ENDPOINTS } from '@/lib/api/endpoints';

export function createProduct(input: ProductInput): Promise<Product> {
  return api.post(API_ENDPOINTS.products, toProductRequest(input), { schema: productSchema });
}

// Mutation + cache invalidation: after a successful create, mark the product
// lists stale so they refetch and show the new item.
export function useCreateProduct() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: createProduct,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: productKeys.lists() });
    },
  });
}
