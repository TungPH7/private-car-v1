import { describe, expect, it } from 'vitest';

import { ADMIN_PROFILE } from '@/testing/api-fixtures';

import { httpMethodSchema, loginCredentialsSchema, permissionSchema, userSchema } from './auth';

describe('auth model schemas', () => {
  it('loginCredentialsSchema validates email + non-empty password', () => {
    expect(loginCredentialsSchema.safeParse({ email: 'a@b.com', password: 'pw' }).success).toBe(
      true,
    );
    expect(
      loginCredentialsSchema.safeParse({ email: 'not-an-email', password: 'pw' }).success,
    ).toBe(false);
    expect(loginCredentialsSchema.safeParse({ email: 'a@b.com', password: '' }).success).toBe(
      false,
    );
  });

  it('httpMethodSchema only accepts known verbs', () => {
    expect(httpMethodSchema.safeParse('GET').success).toBe(true);
    expect(httpMethodSchema.safeParse('FETCH').success).toBe(false);
  });

  it('permissionSchema needs a known method and keeps name, path and module', () => {
    const permission = { name: 'product:delete', path: '/api/products/{id}', module: 'product' };
    expect(permissionSchema.safeParse({ ...permission, method: 'DELETE' }).success).toBe(true);
    expect(permissionSchema.safeParse({ ...permission, method: 'FETCH' }).success).toBe(false);
  });

  it('userSchema reads GET /users/me of the API (UUID id, role, permissions)', () => {
    expect(userSchema.safeParse(ADMIN_PROFILE).success).toBe(true);
    expect(userSchema.parse(ADMIN_PROFILE).id).toBe(ADMIN_PROFILE.id);
    expect(userSchema.safeParse({ ...ADMIN_PROFILE, id: 1 }).success).toBe(false);
    expect(userSchema.safeParse({ ...ADMIN_PROFILE, role: undefined }).success).toBe(false);
  });
});
