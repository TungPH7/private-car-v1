// @vitest-environment node
// The DAL reads env.API_URL (a server variable) and is server-only: jsdom has a window, so t3-env
// would take it for the client and throw. MSW intercepts fetch in Node.
import { http, HttpResponse } from 'msw';
import { expect, test, vi } from 'vitest';

import { changePassword } from '~/features/auth/server/change-password';
import { ApiError } from '~/lib/http/api-error';
import { API_BASE } from '~/testing/api-fixtures';
import { server } from '~/testing/msw/server';

vi.mock('~/lib/auth/auth-headers', () => ({
  authHeaders: vi.fn().mockResolvedValue({ Authorization: 'Bearer t' }),
}));

const URL = `${API_BASE}/api/users/me/password`;
const INPUT = {
  password: 'old-password',
  newPassword: 'new-password',
  confirmNewPassword: 'new-password',
};

test('PUT /api/users/me/password sends the current and the new password; 204 resolves', async () => {
  let sent: unknown;
  let auth: string | null = null;
  server.use(
    http.put(URL, async ({ request }) => {
      sent = await request.json();
      auth = request.headers.get('authorization');
      return new HttpResponse(null, { status: 204 });
    }),
  );

  await expect(changePassword(INPUT)).resolves.toBeUndefined();
  expect(sent).toEqual({ currentPassword: 'old-password', newPassword: 'new-password' });
  expect(auth).toBe('Bearer t');
});

test('a wrong current password (400) → ApiError with the status', async () => {
  server.use(http.put(URL, () => new HttpResponse(null, { status: 400 })));
  await expect(changePassword(INPUT)).rejects.toBeInstanceOf(ApiError);
  await expect(changePassword(INPUT)).rejects.toMatchObject({ status: 400 });
});
