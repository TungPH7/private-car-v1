import { z } from 'zod';

// Schema-first: the Zod schema is the single source of truth and the TS type is
// inferred from it (locked convention: "Zod = single source of truth"). The same
// schema validates API responses at the boundary (see features/product/api).
//
// Source of truth: `ProductResponse` of the Java API (`apps/api`, PCAR-4). `price` is a
// decimal in USD (`app.products.price`, numeric(12,2)); the API keeps it exact.
export const productSchema = z.object({
  id: z.string(),
  name: z.string(),
  /** URL-friendly id, unique, lowercase letters, digits and hyphens. */
  slug: z.string(),
  price: z.number().nonnegative(),
  description: z.string().nullable(),
});
export type Product = z.infer<typeof productSchema>;

/** `PagedResponse` of `GET /products` (convention API-007 of the API); `number` from 0. */
export const productPageSchema = z.object({
  content: z.array(productSchema),
  page: z.object({
    number: z.number(),
    size: z.number(),
    totalElements: z.number(),
    totalPages: z.number(),
  }),
});
export type ProductPage = z.infer<typeof productPageSchema>;

/** The currency of `price` in the API. */
export const PRODUCT_CURRENCY = 'USD';

/** Same rule as `CreateProductRequest.slug` of the API. */
const SLUG_PATTERN = /^[a-z0-9-]+$/;

/** At most two decimals, as numeric(12,2) of the API (checked on cents, no float drift). */
function hasAtMostTwoDecimals(value: number): boolean {
  return Math.abs(value * 100 - Math.round(value * 100)) < 1e-6;
}

/**
 * The create and edit form: the same rules as `CreateProductRequest` and
 * `UpdateProductRequest` of the API, so a bad value shows on its field instead of a 400.
 * `description` stays a string in the form; `toProductRequest` sends '' as null.
 */
export const productInputSchema = z.object({
  name: z.string().trim().min(1).max(255),
  slug: z.string().trim().min(1).max(100).regex(SLUG_PATTERN),
  price: z.number().nonnegative().max(9_999_999_999.99).refine(hasAtMostTwoDecimals),
  description: z.string().max(4000),
});
export type ProductInput = z.infer<typeof productInputSchema>;

/** Body of `POST /products` and `PUT /products/{id}`. */
export interface ProductRequest {
  name: string;
  slug: string;
  price: number;
  description: string | null;
}

export function toProductRequest(input: ProductInput): ProductRequest {
  const description = input.description.trim();
  return {
    name: input.name.trim(),
    slug: input.slug.trim(),
    price: input.price,
    description: description === '' ? null : description,
  };
}

/** The form values of an existing product (edit). */
export function toProductInput(product: Product): ProductInput {
  return {
    name: product.name,
    slug: product.slug,
    price: product.price,
    description: product.description ?? '',
  };
}

/**
 * A price in the locale of the UI (i18n: grouping, decimal mark and currency sign come from the
 * locale, never hard-coded).
 */
export function formatPrice(price: number, locale: string): string {
  return new Intl.NumberFormat(locale, { style: 'currency', currency: PRODUCT_CURRENCY }).format(
    price,
  );
}
