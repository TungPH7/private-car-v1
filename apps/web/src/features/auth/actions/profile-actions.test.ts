// @vitest-environment node
// The DAL reads env.API_URL (a server variable) and is server-only: jsdom has a window, so t3-env
// would take it for the client and throw. MSW intercepts fetch in Node.
import { beforeEach, expect, test, vi } from 'vitest';

import { changePasswordAction, updateProfileAction } from '~/features/auth/actions/profile-actions';
import { changePassword } from '~/features/auth/server/change-password';
import { login } from '~/features/auth/server/login';
import { updateProfile } from '~/features/auth/server/update-profile';
import { getCurrentUser } from '~/lib/auth/get-current-user';
import { getProfile } from '~/lib/auth/get-profile';
import { createSession } from '~/lib/auth/session';
import { ApiError } from '~/lib/http/api-error';
import { ADMIN_ID, ADMIN_PROFILE } from '~/testing/api-fixtures';

vi.mock('~/features/auth/server/change-password', () => ({ changePassword: vi.fn() }));
vi.mock('~/features/auth/server/update-profile', () => ({ updateProfile: vi.fn() }));
vi.mock('~/features/auth/server/login', () => ({ login: vi.fn() }));
vi.mock('~/lib/auth/get-current-user', () => ({ getCurrentUser: vi.fn() }));
vi.mock('~/lib/auth/get-profile', () => ({ getProfile: vi.fn() }));
vi.mock('~/lib/auth/session', () => ({ createSession: vi.fn() }));

const SIGNED_IN = { id: ADMIN_ID, role: 'CUSTOMER' };
const VALID = { name: 'An', phoneNumber: '', avatar: '' };

beforeEach(() => {
  vi.clearAllMocks();
});

test('invalid input → validation + fieldErrors; the API is not called', async () => {
  const s = await updateProfileAction({ name: '', phoneNumber: '', avatar: '' });
  expect(s).toEqual({ status: 'error', reason: 'validation', fieldErrors: { name: 'required' } });
  expect(vi.mocked(updateProfile)).not.toHaveBeenCalled();
});

test('not signed in → auth; the API is not called', async () => {
  vi.mocked(getCurrentUser).mockResolvedValue(null);
  const s = await updateProfileAction(VALID);
  expect(s).toEqual({ status: 'error', reason: 'auth' });
  expect(vi.mocked(updateProfile)).not.toHaveBeenCalled();
});

test('signed in + update OK → success (validated again with the same schema)', async () => {
  vi.mocked(getCurrentUser).mockResolvedValue(SIGNED_IN);
  vi.mocked(updateProfile).mockResolvedValue({ id: ADMIN_ID, name: 'An' });
  const s = await updateProfileAction(VALID);
  expect(s).toEqual({ status: 'success' });
  expect(vi.mocked(updateProfile)).toHaveBeenCalledWith(VALID);
});

test('API 401 (defense in depth) → auth', async () => {
  vi.mocked(getCurrentUser).mockResolvedValue(SIGNED_IN);
  vi.mocked(updateProfile).mockRejectedValue(
    new ApiError({ kind: 'http', status: 401, message: 'x' }),
  );
  expect(await updateProfileAction(VALID)).toEqual({ status: 'error', reason: 'auth' });
});

test('another API error → unknown', async () => {
  vi.mocked(getCurrentUser).mockResolvedValue(SIGNED_IN);
  vi.mocked(updateProfile).mockRejectedValue(
    new ApiError({ kind: 'http', status: 500, message: 'x' }),
  );
  expect(await updateProfileAction(VALID)).toEqual({ status: 'error', reason: 'unknown' });
});

const VALID_PWD = {
  password: 'old-password',
  newPassword: 'new-password',
  confirmNewPassword: 'new-password',
};

test('password: confirmation mismatch → validation (password_mismatch); no API call', async () => {
  const s = await changePasswordAction({ ...VALID_PWD, confirmNewPassword: 'x' });
  expect(s.status).toBe('error');
  if (s.status === 'error' && s.reason === 'validation') {
    expect(s.fieldErrors.confirmNewPassword).toBe('password_mismatch');
  }
  expect(vi.mocked(changePassword)).not.toHaveBeenCalled();
});

test('password: a new password shorter than 8 characters → validation', async () => {
  const s = await changePasswordAction({
    password: 'old-password',
    newPassword: 'short',
    confirmNewPassword: 'short',
  });
  expect(s.status).toBe('error');
  if (s.status === 'error' && s.reason === 'validation') {
    expect(s.fieldErrors.newPassword).toBe('password_too_short');
  }
  expect(vi.mocked(changePassword)).not.toHaveBeenCalled();
});

test('password: not signed in → auth', async () => {
  vi.mocked(getCurrentUser).mockResolvedValue(null);
  expect(await changePasswordAction(VALID_PWD)).toEqual({ status: 'error', reason: 'auth' });
});

test('password changed → success, and this session signs in again with the new password', async () => {
  vi.mocked(getCurrentUser).mockResolvedValue(SIGNED_IN);
  vi.mocked(getProfile).mockResolvedValue({ ...ADMIN_PROFILE, phoneNumber: '' });
  vi.mocked(changePassword).mockResolvedValue(undefined);
  vi.mocked(login).mockResolvedValue({ accessToken: 'new-a', refreshToken: 'new-r' });

  expect(await changePasswordAction(VALID_PWD)).toEqual({ status: 'success' });
  expect(vi.mocked(changePassword)).toHaveBeenCalledWith(VALID_PWD);
  // The API revoked every refresh token, ours too: without a new pair this session ends.
  expect(vi.mocked(login)).toHaveBeenCalledWith({
    email: 'admin@example.com',
    password: 'new-password',
  });
  expect(vi.mocked(createSession)).toHaveBeenCalledWith({
    accessToken: 'new-a',
    refreshToken: 'new-r',
  });
});

test('password changed but signing in again fails → still success (the session ends later)', async () => {
  vi.mocked(getCurrentUser).mockResolvedValue(SIGNED_IN);
  vi.mocked(getProfile).mockResolvedValue({ ...ADMIN_PROFILE, phoneNumber: '' });
  vi.mocked(changePassword).mockResolvedValue(undefined);
  vi.mocked(login).mockRejectedValue(new ApiError({ kind: 'network', message: 'x' }));

  expect(await changePasswordAction(VALID_PWD)).toEqual({ status: 'success' });
  expect(vi.mocked(createSession)).not.toHaveBeenCalled();
});

test('password: a wrong current password (API 400) → unknown', async () => {
  vi.mocked(getCurrentUser).mockResolvedValue(SIGNED_IN);
  vi.mocked(changePassword).mockRejectedValue(
    new ApiError({ kind: 'http', status: 400, message: 'x' }),
  );
  expect(await changePasswordAction(VALID_PWD)).toEqual({ status: 'error', reason: 'unknown' });
  expect(vi.mocked(login)).not.toHaveBeenCalled();
});
