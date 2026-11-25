import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';

import { useCreateProduct } from '@/features/product/api/create-product';
import { ProductFields } from '@/features/product/components/product-fields';
import { type ProductInput, productInputSchema } from '@/features/product/model/product';
import { isApiError } from '@/lib/api/api-error';

// Second form sharing the exact same pattern as LoginForm — proves it generalizes.
// Reuses `productInputSchema` (the rules of the API) as the resolver and `useCreateProduct`
// (folder 08, which invalidates the product list on success). The fields are
// `ProductFields`, shared with the edit form.
export function ProductCreateForm() {
  const createProduct = useCreateProduct();
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<ProductInput>({
    resolver: zodResolver(productInputSchema),
    defaultValues: { name: '', slug: '', price: 0, description: '' },
  });

  const submit = handleSubmit((values) => {
    createProduct.mutate(values, { onSuccess: () => reset() });
  });

  return (
    <form onSubmit={(event) => void submit(event)} noValidate>
      <ProductFields register={register} errors={errors} />
      <button type="submit" disabled={createProduct.isPending}>
        {createProduct.isPending ? 'Creating…' : 'Create product'}
      </button>
      {createProduct.isError ? (
        <p role="alert">
          {isApiError(createProduct.error)
            ? createProduct.error.message
            : 'Failed to create product'}
        </p>
      ) : null}
      {createProduct.isSuccess ? <p role="status">Product created.</p> : null}
    </form>
  );
}
