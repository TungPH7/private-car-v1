import { expect, test } from 'vitest';

import { type Product } from '~/features/product/model/product';
import { renderWithProviders, screen } from '~/testing/render';

import { ProductList } from './product-list';

const products: Product[] = [
  { id: 'a1', name: 'Sản phẩm A', price: 800, originalPrice: 1200, images: [] },
  { id: 'b2', name: 'Sản phẩm B', price: 50, originalPrice: 50, images: [] },
];

test('render tên + giá bán + ảnh (alt=tên); gạch giá gốc khi đang sale', () => {
  renderWithProviders(<ProductList products={products} emptyLabel="No products" locale="en" />);
  expect(screen.getByText('Sản phẩm A')).toBeInTheDocument();
  expect(screen.getByText('$800.00')).toBeInTheDocument();
  expect(screen.getByText('$1,200.00').tagName).toBe('DEL'); // giá gốc gạch ngang
  // folder 20: next/image render <img alt={name}> (ảnh placeholder picsum vì images rỗng).
  expect(screen.getByAltText('Sản phẩm A')).toBeInTheDocument();
});

test('KHÔNG gạch khi giá gốc = giá bán', () => {
  renderWithProviders(
    <ProductList products={[products[1]!]} emptyLabel="No products" locale="en" />,
  );
  expect(screen.getByText('$50.00')).toBeInTheDocument();
  expect(screen.queryByText('$50.00', { selector: 'del' })).not.toBeInTheDocument();
});

test('danh sách rỗng → hiện emptyLabel', () => {
  renderWithProviders(<ProductList products={[]} emptyLabel="Chưa có sản phẩm" locale="vi" />);
  expect(screen.getByText('Chưa có sản phẩm')).toBeInTheDocument();
});
