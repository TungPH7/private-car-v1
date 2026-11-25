// @vitest-environment node
// The DAL reads env.API_URL (a server variable) and is server-only: jsdom has a window, so t3-env
// would take it for the client and throw. MSW intercepts fetch in Node.
import { http, HttpResponse } from 'msw';
import { expect, test, vi } from 'vitest';

import { getProfile } from '~/lib/auth/get-profile';
import { getSession } from '~/lib/auth/session';
import { ADMIN_ID, ADMIN_PROFILE, API_BASE } from '~/testing/api-fixtures';
import { server } from '~/testing/msw/server';

vi.mock('~/lib/auth/session', () => ({ getSession: vi.fn() }));

const URL = `${API_BASE}/api/users/me`;

test('not signed in → null, without calling the API', async () => {
  vi.mocked(getSession).mockResolvedValue(null);
  let hits = 0;
  server.use(
    http.get(URL, () => {
      hits++;
      return HttpResponse.json(ADMIN_PROFILE);
    }),
  );
  await expect(getProfile()).resolves.toBeNull();
  expect(hits).toBe(0);
});

test('signed in → the profile with its role and permissions, sent with the bearer token', async () => {
  vi.mocked(getSession).mockResolvedValue({ accessToken: 'a', refreshToken: 'r' });
  let auth: string | null = null;
  server.use(
    http.get(URL, ({ request }) => {
      auth = request.headers.get('authorization');
      return HttpResponse.json(ADMIN_PROFILE);
    }),
  );

  const user = await getProfile();

  expect(auth).toBe('Bearer a');
  expect(user?.id).toBe(ADMIN_ID);
  expect(user?.phoneNumber).toBe(''); // null of the API → '' for the profile form
  expect(user?.role.name).toBe('ADMIN');
  expect(user?.role.permissions).toContainEqual({
    module: 'product',
    method: 'DELETE',
    path: '/api/products/{id}',
  });
});

test('an expired token (401) → null, without throwing (the RSC does not crash)', async () => {
  vi.mocked(getSession).mockResolvedValue({ accessToken: 'expired', refreshToken: 'r' });
  server.use(http.get(URL, () => new HttpResponse(null, { status: 401 })));
  await expect(getProfile()).resolves.toBeNull();
});
