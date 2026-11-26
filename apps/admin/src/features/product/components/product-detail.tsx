import { type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';

import { paths } from '@/config/paths';
import { useProduct } from '@/features/product/api/get-product';
import { formatPrice } from '@/features/product/model/product';
import { isApiError } from '@/lib/api/api-error';
import { useLocale } from '@/lib/i18n/use-locale';
import { invariant } from '@/utils/assert';

interface ProductDetailProps {
  /** Optional action slot (e.g. RBAC-gated Edit/Delete) injected by the app layer
   *  so this product component stays unaware of the auth feature (folder 05). */
  actions?: ReactNode;
}

export function ProductDetail({ actions }: ProductDetailProps) {
  const { t } = useTranslation('product');
  const { locale } = useLocale();
  const { productId } = useParams();
  invariant(productId, 'productId route param is required');

  const { data: product, isPending, isError, error } = useProduct(productId);

  if (isPending) {
    return <p role="status">Loading product…</p>;
  }
  if (isError) {
    const notFound = isApiError(error) && error.status === 404;
    return (
      <section>
        <h2>{notFound ? 'Product not found' : 'Something went wrong'}</h2>
        <p role="alert">{isApiError(error) ? error.message : 'Unknown error'}</p>
        <Link to={paths.products.getHref()}>Back to products</Link>
      </section>
    );
  }

  return (
    <section>
      <h2>{product.name}</h2>
      <p>{t('price', { price: formatPrice(product.price, locale) })}</p>
      <p>{t('slug', { slug: product.slug })}</p>
      {product.description !== null ? <p>{product.description}</p> : null}
      {actions ? <p>{actions}</p> : null}
      <Link to={paths.products.getHref()}>Back to products</Link>
    </section>
  );
}
