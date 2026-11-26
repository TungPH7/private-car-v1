import { expect, test } from 'vitest';

import { profileSchema } from '~/lib/auth/profile';
import { ADMIN_ID, ADMIN_PROFILE } from '~/testing/api-fixtures';

test('profileSchema keeps what the UI needs from GET /api/users/me', () => {
  const r = profileSchema.safeParse({
    ...ADMIN_PROFILE,
    phoneNumber: '+84901234567',
    avatar: 'https://example.com/a.png',
  });
  expect(r.success).toBe(true);
  if (r.success) {
    expect(r.data).toEqual({
      id: ADMIN_ID,
      email: 'admin@example.com',
      name: 'Admin',
      phoneNumber: '+84901234567',
      avatar: 'https://example.com/a.png',
      role: {
        name: 'ADMIN',
        permissions: [
          { module: 'product', method: 'POST', path: '/api/products' },
          { module: 'product', method: 'DELETE', path: '/api/products/{id}' },
          { module: 'product', method: 'PUT', path: '/api/products/{id}' },
        ],
      },
    });
    // The permission name of the API is not part of the DTO: the UI matches method and path.
    expect('name' in (r.data.role.permissions[0] ?? {})).toBe(false);
  }
});

test('profileSchema: a missing phone number (null) becomes an empty string for the form', () => {
  const r = profileSchema.parse(ADMIN_PROFILE);
  expect(r.phoneNumber).toBe('');
  expect(r.avatar).toBeNull();
});

test('profileSchema: no role → fail (validation at the boundary catches drift)', () => {
  expect(profileSchema.safeParse({ id: ADMIN_ID, email: 'a@b.com', name: 'X' }).success).toBe(
    false,
  );
});
