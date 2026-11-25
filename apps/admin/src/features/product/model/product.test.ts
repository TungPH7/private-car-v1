import { describe, expect, it } from 'vitest';

import { apiPage, apiProduct } from '@/testing/api-fixtures';

import {
  formatPrice,
  productInputSchema,
  productPageSchema,
  productSchema,
  toProductInput,
  toProductRequest,
} from './product';

// Intl puts a no-break space between the amount and the sign in some locales: \s matches it.
const plain = (s: string) => s.replace(/\s/g, ' ');

describe('formatPrice', () => {
  it('formats the USD price of the API in the locale of the UI', () => {
    expect(plain(formatPrice(19.99, 'en'))).toBe('$19.99');
    expect(plain(formatPrice(1200, 'en'))).toBe('$1,200.00');
    expect(plain(formatPrice(19.99, 'vi'))).toBe('19,99 US$');
  });
});

describe('productSchema and productPageSchema', () => {
  it('accept a ProductResponse and a PagedResponse of the API', () => {
    expect(productSchema.safeParse(apiProduct()).success).toBe(true);
    expect(productPageSchema.safeParse(apiPage([apiProduct()])).success).toBe(true);
  });

  it('reject a negative price', () => {
    expect(productSchema.safeParse(apiProduct({ price: -1 })).success).toBe(false);
  });
});

describe('productInputSchema (the rules of the API)', () => {
  const valid = { name: 'Widget', slug: 'widget-1', price: 19.99, description: '' };

  it('accepts a valid product', () => {
    expect(productInputSchema.safeParse(valid).success).toBe(true);
  });

  it('rejects a slug that is not lowercase letters, digits and hyphens', () => {
    expect(productInputSchema.safeParse({ ...valid, slug: 'Widget 1' }).success).toBe(false);
  });

  it('rejects a negative price and more than two decimals', () => {
    expect(productInputSchema.safeParse({ ...valid, price: -5 }).success).toBe(false);
    expect(productInputSchema.safeParse({ ...valid, price: 1.234 }).success).toBe(false);
  });

  it('rejects an empty name', () => {
    expect(productInputSchema.safeParse({ ...valid, name: '  ' }).success).toBe(false);
  });
});

describe('toProductRequest and toProductInput', () => {
  it('sends an empty description as null and trims the text fields', () => {
    expect(
      toProductRequest({ name: ' Widget ', slug: ' widget ', price: 5, description: '  ' }),
    ).toEqual({ name: 'Widget', slug: 'widget', price: 5, description: null });
  });

  it('fills the form from a product, a null description as an empty string', () => {
    expect(toProductInput(productSchema.parse(apiProduct({ description: null })))).toEqual({
      name: 'Test Widget',
      slug: 'test-widget',
      price: 19.99,
      description: '',
    });
  });
});
