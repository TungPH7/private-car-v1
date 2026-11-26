import { type FieldErrors, type UseFormRegister } from 'react-hook-form';
import { useTranslation } from 'react-i18next';

import { TextField } from '@/components/form/text-field';
import { type ProductInput } from '@/features/product/model/product';

interface ProductFieldsProps {
  register: UseFormRegister<ProductInput>;
  errors: FieldErrors<ProductInput>;
}

/** Step of the price input: cents, as numeric(12,2) of the API. */
const PRICE_STEP = '0.01';

// The fields of a product, shared by the create and the edit form so both ask for exactly what
// `CreateProductRequest` and `UpdateProductRequest` of the API take. Labels come from the
// `product` namespace (i18n); `valueAsNumber` turns the price into a number for the schema.
export function ProductFields({ register, errors }: ProductFieldsProps) {
  const { t } = useTranslation('product');
  return (
    <>
      <TextField label={t('fieldName')} error={errors.name?.message} {...register('name')} />
      <TextField label={t('fieldSlug')} error={errors.slug?.message} {...register('slug')} />
      <TextField
        label={t('fieldPrice')}
        type="number"
        min={0}
        step={PRICE_STEP}
        error={errors.price?.message}
        {...register('price', { valueAsNumber: true })}
      />
      <TextField
        label={t('fieldDescription')}
        error={errors.description?.message}
        {...register('description')}
      />
    </>
  );
}
