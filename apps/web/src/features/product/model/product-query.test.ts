import { describe, expect, it } from 'vitest';

import {
  productListQuerySchema,
  productQueryToApiParams,
  productQueryToParams,
  productSearchNameSchema,
} from '~/features/product/model/product-query';

describe('productListQuerySchema (searchParams — tha thứ)', () => {
  it('thiếu hết → mặc định an toàn', () => {
    expect(productListQuerySchema.parse({})).toEqual({
      page: 1,
      sortBy: 'createdAt',
      orderBy: 'desc',
    });
  });

  it('coerce page chuỗi → số', () => {
    expect(productListQuerySchema.parse({ page: '3' }).page).toBe(3);
  });

  it('page rác → catch về 1 (KHÔNG ném)', () => {
    expect(productListQuerySchema.parse({ page: 'abc' }).page).toBe(1);
    expect(productListQuerySchema.parse({ page: '-2' }).page).toBe(1);
  });

  it('enum sai → catch về mặc định', () => {
    const q = productListQuerySchema.parse({ sortBy: 'bogus', orderBy: 'x' });
    expect(q.sortBy).toBe('createdAt');
    expect(q.orderBy).toBe('desc');
  });

  it('name < 2 ký tự → bị loại (sanitize)', () => {
    expect(productListQuerySchema.parse({ name: 'a' }).name).toBeUndefined();
  });

  it('name hợp lệ → giữ (đã trim)', () => {
    expect(productListQuerySchema.parse({ name: '  Áo  ' }).name).toBe('Áo');
  });
});

describe('productSearchNameSchema (form — hiện lỗi)', () => {
  it('< 2 ký tự → lỗi code "too_short"', () => {
    const r = productSearchNameSchema.safeParse('a');
    expect(r.success).toBe(false);
    if (!r.success) expect(r.error.issues[0]?.message).toBe('too_short');
  });

  it('>= 2 ký tự → ok', () => {
    expect(productSearchNameSchema.safeParse('Áo').success).toBe(true);
  });
});

describe('productQueryToParams (→ URL gọn, bỏ mặc định)', () => {
  it('toàn mặc định → {} (URL sạch)', () => {
    expect(productQueryToParams(productListQuerySchema.parse({}))).toEqual({});
  });

  it('chỉ giữ giá trị KHÁC mặc định', () => {
    const q = productListQuerySchema.parse({ page: '2', name: 'Áo', sortBy: 'price' });
    expect(productQueryToParams(q)).toEqual({ page: '2', name: 'Áo', sortBy: 'price' });
  });
});

describe('productQueryToApiParams (→ GET /api/products of the Java API)', () => {
  it('defaults → page 0, the page size, newest first', () => {
    const params = productQueryToApiParams(productListQuerySchema.parse({}), 10);
    expect(Object.fromEntries(params)).toEqual({ page: '0', size: '10', sort: 'createdAt,desc' });
  });

  it('the 1-based page of the UI becomes the 0-based page of the API, with sort and name', () => {
    const q = productListQuerySchema.parse({
      page: '3',
      name: 'Áo',
      sortBy: 'price',
      orderBy: 'asc',
    });
    expect(Object.fromEntries(productQueryToApiParams(q, 10))).toEqual({
      page: '2',
      size: '10',
      sort: 'price,asc',
      name: 'Áo',
    });
  });

  it('sorts by name, which the API offers (it has no "on sale" order)', () => {
    const q = productListQuerySchema.parse({ sortBy: 'name', orderBy: 'asc' });
    expect(productQueryToApiParams(q, 10).get('sort')).toBe('name,asc');
    expect(productListQuerySchema.parse({ sortBy: 'sale' }).sortBy).toBe('createdAt');
  });
});
