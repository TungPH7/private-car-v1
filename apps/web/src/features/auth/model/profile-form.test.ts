import { expect, test } from 'vitest';

import { changePasswordSchema, updateProfileSchema } from '~/features/auth/model/profile-form';
import { getFieldErrorCodes } from '~/lib/validation/format-zod-error';

test('valid: name + phone + https avatar', () => {
  const r = updateProfileSchema.safeParse({
    name: 'An',
    phoneNumber: '0123456789',
    avatar: 'https://x.com/a.png',
  });
  expect(r.success).toBe(true);
});

test('phone rỗng + avatar rỗng → hợp lệ (optional-ish khớp backend)', () => {
  expect(updateProfileSchema.safeParse({ name: 'An', phoneNumber: '', avatar: '' }).success).toBe(
    true,
  );
});

test('name rỗng / phone ngắn / avatar sai → code trung tính (share i18n)', () => {
  const r = updateProfileSchema.safeParse({ name: '', phoneNumber: '123', avatar: 'not-a-url' });
  expect(r.success).toBe(false);
  if (!r.success) {
    expect(getFieldErrorCodes(r.error)).toEqual({
      name: 'required',
      phoneNumber: 'invalid_phone',
      avatar: 'invalid_url',
    });
  }
});

test('name quá dài → too_long', () => {
  const r = updateProfileSchema.safeParse({ name: 'x'.repeat(101), phoneNumber: '', avatar: '' });
  expect(r.success).toBe(false);
  if (!r.success) expect(getFieldErrorCodes(r.error).name).toBe('too_long');
});

test('đổi mật khẩu hợp lệ (new === confirm)', () => {
  const r = changePasswordSchema.safeParse({
    password: 'old123',
    newPassword: 'new12345',
    confirmNewPassword: 'new12345',
  });
  expect(r.success).toBe(true);
});

test('⭐ confirm LỆCH → password_mismatch ở confirmNewPassword (superRefine cross-field)', () => {
  const r = changePasswordSchema.safeParse({
    password: 'old123',
    newPassword: 'new12345',
    confirmNewPassword: 'nope45678',
  });
  expect(r.success).toBe(false);
  if (!r.success) expect(getFieldErrorCodes(r.error).confirmNewPassword).toBe('password_mismatch');
});

test('a new password under 8 characters → password_too_short', () => {
  const r = changePasswordSchema.safeParse({
    password: 'old123',
    newPassword: 'abcdefg',
    confirmNewPassword: 'abcdefg',
  });
  expect(r.success).toBe(false);
  if (!r.success) expect(getFieldErrorCodes(r.error).newPassword).toBe('password_too_short');
});

test('a phone with letters → invalid_phone (the rule of the API)', () => {
  const r = updateProfileSchema.safeParse({ name: 'An', phoneNumber: 'call-me-now', avatar: '' });
  expect(r.success).toBe(false);
  if (!r.success) expect(getFieldErrorCodes(r.error).phoneNumber).toBe('invalid_phone');
});

test('an avatar that is not https → invalid_url (the API takes https only)', () => {
  for (const avatar of ['http://x.com/a.png', 'javascript:alert(1)']) {
    const r = updateProfileSchema.safeParse({ name: 'An', phoneNumber: '', avatar });
    expect(r.success).toBe(false);
    if (!r.success) expect(getFieldErrorCodes(r.error).avatar).toBe('invalid_url');
  }
});

test('a new password over 72 characters → too_long (BCrypt of the API)', () => {
  const long = 'x'.repeat(73);
  const r = changePasswordSchema.safeParse({
    password: 'old-password',
    newPassword: long,
    confirmNewPassword: long,
  });
  expect(r.success).toBe(false);
  if (!r.success) expect(getFieldErrorCodes(r.error).newPassword).toBe('too_long');
});
