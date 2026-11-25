import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router';

import { paths } from '@/config/paths';
import { useUpdateProduct } from '@/features/product/api/update-product';
import { ProductFields } from '@/features/product/components/product-fields';
import {
  type Product,
  type ProductInput,
  productInputSchema,
  toProductInput,
} from '@/features/product/model/product';
import { isApiError } from '@/lib/api/api-error';

interface ProductEditFormProps {
  product: Product;
}

// Same pattern + schema as ProductCreateForm (folder 11), but pre-filled from the
// existing product and wired to `useUpdateProduct`. On success it navigates to the
// detail page — which renders instantly because the mutation seeded its cache.
export function ProductEditForm({ product }: ProductEditFormProps) {
  const navigate = useNavigate();
  const updateProduct = useUpdateProduct(product.id);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ProductInput>({
    resolver: zodResolver(productInputSchema),
    defaultValues: toProductInput(product),
  });

  const submit = handleSubmit((values) => {
    updateProduct.mutate(values, {
      onSuccess: () => {
        void navigate(paths.productDetail.getHref(product.id));
      },
    });
  });

  return (
    <form onSubmit={(event) => void submit(event)} noValidate>
      <ProductFields register={register} errors={errors} />
      <button type="submit" disabled={updateProduct.isPending}>
        {updateProduct.isPending ? 'Saving…' : 'Save changes'}
      </button>
      {updateProduct.isError ? (
        <p role="alert">
          {isApiError(updateProduct.error)
            ? updateProduct.error.message
            : 'Failed to update product'}
        </p>
      ) : null}
    </form>
  );
}
