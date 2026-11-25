import { expect, test } from 'vitest';

import { apiPage, apiProduct } from '~/testing/api-fixtures';

import {
  formatPrice,
  mapToProduct,
  mapToProductList,
  productImageSrc,
  productListResponseSchema,
} from './product';

const ID = '0193c1f4-7b9a-7a3c-8d2e-aa1c98dead01';

test('the schema reads a PagedResponse of the Java API and drops the fields the UI does not use', () => {
  const parsed = productListResponseSchema.parse(
    apiPage([apiProduct({ id: ID, name: 'Postgres Guide', price: 29.99 })], {
      number: 1,
      size: 10,
      totalElements: 11,
    }),
  );
  expect(parsed.content[0]).toEqual({ id: ID, name: 'Postgres Guide', price: 29.99 });
  expect(parsed.page).toEqual({ number: 1, size: 10, totalElements: 11, totalPages: 2 });
});

test('mapToProduct fills the UI shape; fields the API has not got yet get a fallback', () => {
  expect(mapToProduct({ id: ID, name: 'Postgres Guide', price: 29.99 })).toEqual({
    id: ID,
    name: 'Postgres Guide',
    price: 29.99,
    originalPrice: 29.99, // no compare-at price in the API yet: nothing shows as on sale
    images: [], // no images in the API yet: productImageSrc gives a placeholder
  });
});

test('mapToProductList turns the 0-based page of the API into the 1-based page of the UI', () => {
  const parsed = productListResponseSchema.parse(
    apiPage([apiProduct()], { number: 2, size: 10, totalElements: 25 }),
  );
  expect(mapToProductList(parsed)).toMatchObject({ page: 3, totalPages: 3, totalItems: 25 });
  expect(mapToProductList(parsed).items).toHaveLength(1);
});

test('formatPrice formats the USD price of the API in the locale of the page', () => {
  // Intl puts a no-break space between the amount and the sign in some locales.
  const plain = (s: string) => s.replace(/ /g, ' ');
  expect(plain(formatPrice(29.99, 'en'))).toBe('$29.99');
  expect(plain(formatPrice(1200, 'en'))).toBe('$1,200.00');
  expect(plain(formatPrice(29.99, 'vi'))).toBe('29,99 US$');
});

test('productImageSrc: a placeholder or missing image → picsum by id; a real CDN URL → as it is', () => {
  expect(productImageSrc({ id: 'a7', images: ['https://example.com/img2.jpg'] })).toBe(
    'https://picsum.photos/seed/tungph7-a7/600/400',
  );
  expect(productImageSrc({ id: ID, images: [] })).toBe(
    `https://picsum.photos/seed/tungph7-${ID}/600/400`,
  );
  expect(productImageSrc({ id: 'a1', images: ['https://cdn.myshop.io/p/1.webp'] })).toBe(
    'https://cdn.myshop.io/p/1.webp',
  );
});
