import { z } from 'zod';

/**
 * Schema at the BOUNDARY: only the fields we use, so Zod drops the rest (`slug`, `description`,
 * `createdAt`…) — the "minimal DTO" step right at the boundary.
 *
 * Source of truth: `ProductResponse` of the Java API (`apps/api`, PCAR-4): `id` is a UUID, `price`
 * a decimal in USD (`app.products.price`, numeric(12,2)).
 */
const backendProductSchema = z.object({
  id: z.string().min(1),
  name: z.string(),
  price: z.number(),
});

/**
 * `PagedResponse` of `GET /api/products` (convention API-007 of the API):
 * `{ content, page: { number (from 0), size, totalElements, totalPages } }`.
 */
export const productListResponseSchema = z.object({
  content: z.array(backendProductSchema),
  page: z.object({
    number: z.number(),
    size: z.number(),
    totalElements: z.number(),
    totalPages: z.number(),
  }),
});

type BackendProduct = z.infer<typeof backendProductSchema>;
type BackendProductList = z.infer<typeof productListResponseSchema>;

/** Product endpoints of the Java API (`apps/api`, PCAR-4). */
export const PRODUCT_ENDPOINTS = {
  /** GET (public): a page of products. */
  list: '/api/products',
  /** DELETE (`product:delete`) → 204. */
  item: (productId: string) => `/api/products/${encodeURIComponent(productId)}`,
} as const;

/** The currency of `price` in the API. */
export const PRODUCT_CURRENCY = 'USD';

/** Domain DTO: what the UI needs. */
export interface Product {
  id: string;
  name: string;
  /** Selling price, in `PRODUCT_CURRENCY`. */
  price: number;
  /** Price before a discount; equal to `price` when the product is not on sale. */
  originalPrice: number;
  /** Image URLs; may be empty (then `productImageSrc` gives a placeholder). */
  images: string[];
}

/** Result of the list DAL (folder 13): DTOs + paging meta, `page` counted from 1 as in the UI. */
export interface ProductListResult {
  items: Product[];
  page: number;
  totalPages: number;
  totalItems: number;
}

/**
 * Maps a product of the API to the UI shape. The API has no compare-at price and no images yet:
 * `originalPrice` falls back to `price` (nothing shows as on sale) and `images` to none (a
 * placeholder image), so the UI renders every field it needs. Pure → tested directly.
 */
export function mapToProduct(raw: BackendProduct): Product {
  return {
    id: raw.id,
    name: raw.name,
    price: raw.price,
    originalPrice: raw.price,
    images: [],
  };
}

/** Maps a page of the API (0-based) to the list result of the UI (1-based). */
export function mapToProductList(raw: BackendProductList): ProductListResult {
  return {
    items: raw.content.map(mapToProduct),
    page: raw.page.number + 1,
    totalPages: raw.page.totalPages,
    totalItems: raw.page.totalElements,
  };
}

// Image hosts of fake URLs that do not load. Matched EXACTLY on the hostname (not a suffix).
const PLACEHOLDER_IMAGE_HOSTS = new Set(['example.com', 'example.org', 'example.net']);

/**
 * Image source of a product card (folder 20). A missing first image, one that is not an absolute
 * URL, or one on a placeholder host → a DETERMINISTIC placeholder by `id` (picsum), so next/image
 * has a real picture. A real CDN image goes through as it is (add its host to
 * `images.remotePatterns` of next.config). Pure → tested.
 */
export function productImageSrc(product: Pick<Product, 'id' | 'images'>): string {
  const first = product.images[0];
  if (first && URL.canParse(first) && !PLACEHOLDER_IMAGE_HOSTS.has(new URL(first).hostname)) {
    return first;
  }
  return `https://picsum.photos/seed/tungph7-${product.id}/600/400`;
}

/**
 * Formats a price in the locale of the page (i18n: grouping, decimal mark and currency sign come
 * from the locale, never hard-coded). Product lists render on the server only, so the ICU of Node
 * decides alone and no hydration can disagree.
 */
export function formatPrice(amount: number, locale: string): string {
  return new Intl.NumberFormat(locale, { style: 'currency', currency: PRODUCT_CURRENCY }).format(
    amount,
  );
}
