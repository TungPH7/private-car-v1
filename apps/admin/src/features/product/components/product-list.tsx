import { useDeferredValue } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { paths } from '@/config/paths';
import { prefetchProduct } from '@/features/product/api/get-product';
import { useProducts } from '@/features/product/api/get-products';
import { useProductFilters } from '@/features/product/hooks/use-product-filters';
import { formatPrice } from '@/features/product/model/product';
import { isApiError } from '@/lib/api/api-error';
import { useLocale } from '@/lib/i18n/use-locale';

// UI-agnostic (Phase 1.1): plain markup only. Server data comes from TanStack
// Query (folder 08); the search term is URL state via nuqs (folder 09). Strings
// use the lazy-loaded `product` namespace (folder 14) — the `count` key shows
// i18next pluralization (`count_one`/`count_other`).
export function ProductList() {
  const { t } = useTranslation('product');
  const { locale } = useLocale();
  const queryClient = useQueryClient();
  const { search, setSearch } = useProductFilters();
  // Query with the DEFERRED term so the input stays responsive: typing updates
  // instantly while the request for the new term follows at lower priority.
  // (Vercel rerender-use-deferred-value.) The API filters by name over every
  // product; the list keeps the previous result on screen while it loads.
  const deferredSearch = useDeferredValue(search);
  const { data: page, isPending, isError, error } = useProducts(deferredSearch);
  const filtered = page?.content;

  return (
    <section>
      <h2>{t('title')}</h2>

      <label>
        Search:{' '}
        <input
          type="search"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder="Filter by name…"
        />
      </label>

      {isPending ? <p role="status">{t('loading')}</p> : null}
      {isError ? (
        <p role="alert">
          Failed to load products: {isApiError(error) ? error.message : 'Unknown error'}
        </p>
      ) : null}
      {filtered ? (
        filtered.length > 0 ? (
          <>
            <p>{t('count', { count: page?.page.totalElements ?? filtered.length })}</p>
            <ul>
              {filtered.map((product) => (
                <li key={product.id}>
                  <Link
                    to={paths.productDetail.getHref(product.id)}
                    // Preload the detail on intent (hover/focus) → instant navigation.
                    onMouseEnter={() => void prefetchProduct(queryClient, product.id)}
                    onFocus={() => void prefetchProduct(queryClient, product.id)}
                  >
                    {product.name}
                  </Link>{' '}
                  — {formatPrice(product.price, locale)}
                </li>
              ))}
            </ul>
          </>
        ) : (
          <p>{t('empty')}</p>
        )
      ) : null}
    </section>
  );
}
