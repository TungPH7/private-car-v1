// @vitest-environment node
// The DAL reads env.API_URL (a server variable) and is server-only: jsdom has a window, so t3-env
// would take it for the client and throw. MSW intercepts fetch in Node.
import { http, HttpResponse } from 'msw';
import { expect, test, vi } from 'vitest';

import { updateProfile } from '~/features/auth/server/update-profile';
import { ApiError } from '~/lib/http/api-error';
import { ADMIN_ID, ADMIN_PROFILE, API_BASE } from '~/testing/api-fixtures';
import { server } from '~/testing/msw/server';

vi.mock('~/lib/auth/auth-headers', () => ({
  authHeaders: vi.fn().mockResolvedValue({ Authorization: 'Bearer t' }),
}));

const URL = `${API_BASE}/api/users/me`;

test('PUT /api/users/me sends empty fields as null and returns { id, name }', async () => {
  let sent: unknown;
  server.use(
    http.put(URL, async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json({ ...ADMIN_PROFILE, name: 'An' });
    }),
  );

  await expect(updateProfile({ name: 'An', phoneNumber: '', avatar: '' })).resolves.toEqual({
    id: ADMIN_ID,
    name: 'An',
  });
  expect(sent).toEqual({ name: 'An', phoneNumber: null, avatar: null });
});

test('filled fields are sent as they are', async () => {
  let sent: unknown;
  server.use(
    http.put(URL, async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json(ADMIN_PROFILE);
    }),
  );

  await updateProfile({
    name: 'An',
    phoneNumber: '+84901234567',
    avatar: 'https://example.com/a.png',
  });

  expect(sent).toEqual({
    name: 'An',
    phoneNumber: '+84901234567',
    avatar: 'https://example.com/a.png',
  });
});

test('401 → ApiError (http)', async () => {
  server.use(http.put(URL, () => new HttpResponse(null, { status: 401 })));
  await expect(updateProfile({ name: 'An', phoneNumber: '', avatar: '' })).rejects.toBeInstanceOf(
    ApiError,
  );
});
