import { expect, test, vi } from 'vitest';

import { renderWithProviders, screen } from '~/testing/render';

import { DeleteProductButton } from './delete-product-button';

vi.mock('~/features/product/actions/product-actions', () => ({
  refreshProductsAction: vi.fn(),
  deleteProductAction: vi.fn(),
}));

const messages = { validation: 'invalid', auth: 'need login', unknown: 'oops' };

test('render nút xoá + input ẩn productId; idle → chưa hiện lỗi', () => {
  const { container } = renderWithProviders(
    <DeleteProductButton
      productId="0193c1f4-7b9a-7a3c-8d2e-aa1c98dead29"
      label="Delete"
      messages={messages}
    />,
  );
  expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
  expect(container.querySelector('input[name="productId"]')).toHaveValue(
    '0193c1f4-7b9a-7a3c-8d2e-aa1c98dead29',
  );
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});
