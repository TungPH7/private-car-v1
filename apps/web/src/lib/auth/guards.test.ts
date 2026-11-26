// @vitest-environment node
import { expect, test, vi } from 'vitest';

import { getProfile } from '~/lib/auth/get-profile';
import { requireModule, requireUser } from '~/lib/auth/guards';
import { redirect } from '~/i18n/navigation';

vi.mock('~/lib/auth/get-profile', () => ({ getProfile: vi.fn() }));
// redirect THẬT ném NEXT_REDIRECT → mock ném sentinel để assert "đã redirect" + tham số.
vi.mock('~/i18n/navigation', () => ({
  redirect: vi.fn(() => {
    throw new Error('REDIRECT');
  }),
}));
vi.mock('next-intl/server', () => ({ getLocale: vi.fn().mockResolvedValue('en') }));

const ADMIN = {
  id: '0193c1f4-7b9a-7a3c-8d2e-aa1c98dead10',
  email: 'a@b.com',
  name: 'A',
  phoneNumber: '',
  avatar: null,
  role: {
    name: 'ADMIN',
    permissions: [{ module: 'product', path: '/api/products/{id}', method: 'DELETE' }],
  },
};
const CLIENT = {
  id: '0193c1f4-7b9a-7a3c-8d2e-aa1c98dead11',
  email: 'c@b.com',
  name: 'C',
  phoneNumber: '',
  avatar: null,
  role: { name: 'CUSTOMER', permissions: [] },
};

test('requireUser: chưa đăng nhập → redirect /login', async () => {
  vi.mocked(getProfile).mockResolvedValue(null);
  await expect(requireUser()).rejects.toThrow('REDIRECT');
  expect(vi.mocked(redirect)).toHaveBeenCalledWith({ href: '/login', locale: 'en' });
});

test('requireUser: đã đăng nhập → trả user', async () => {
  vi.mocked(getProfile).mockResolvedValue(ADMIN);
  await expect(requireUser()).resolves.toEqual(ADMIN);
});

test('requireModule: thiếu module → redirect /forbidden', async () => {
  vi.mocked(getProfile).mockResolvedValue(CLIENT);
  await expect(requireModule('product')).rejects.toThrow('REDIRECT');
  expect(vi.mocked(redirect)).toHaveBeenCalledWith({ href: '/forbidden', locale: 'en' });
});

test('requireModule: có module → trả user', async () => {
  vi.mocked(getProfile).mockResolvedValue(ADMIN);
  await expect(requireModule('product')).resolves.toEqual(ADMIN);
});
