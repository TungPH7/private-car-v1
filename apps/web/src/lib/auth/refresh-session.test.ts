// @vitest-environment node
// The DAL reads env.API_URL (a server variable) and is server-only: jsdom has a window, so t3-env
// would take it for the client and throw. MSW intercepts fetch in Node.
import { http, HttpResponse } from 'msw';
import { expect, test, vi } from 'vitest';

import { refreshSession } from '~/lib/auth/refresh-session';
import { createSession, getSession } from '~/lib/auth/session';
import { API_BASE, apiTokenPair } from '~/testing/api-fixtures';
import { server } from '~/testing/msw/server';

vi.mock('~/lib/auth/session', () => ({ getSession: vi.fn(), createSession: vi.fn() }));

const URL = `${API_BASE}/api/auth/tokens`;

test('no session → null, without calling the API', async () => {
  vi.mocked(getSession).mockResolvedValue(null);
  await expect(refreshSession()).resolves.toBeNull();
  expect(vi.mocked(createSession)).not.toHaveBeenCalled();
});

test('refresh_token grant → new pair, sealed into the session cookie', async () => {
  vi.mocked(getSession).mockResolvedValue({ accessToken: 'old-a', refreshToken: 'old-r' });
  let sent: unknown;
  server.use(
    http.post(URL, async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json(apiTokenPair('new-a', 'new-r'));
    }),
  );

  const next = await refreshSession();

  expect(sent).toEqual({ grantType: 'refresh_token', refreshToken: 'old-r' });
  expect(next).toEqual({ accessToken: 'new-a', refreshToken: 'new-r' });
  expect(vi.mocked(createSession)).toHaveBeenCalledWith({
    accessToken: 'new-a',
    refreshToken: 'new-r',
  });
});

test('a spent or revoked refresh token (401) → null, the session is not resealed', async () => {
  vi.mocked(getSession).mockResolvedValue({ accessToken: 'a', refreshToken: 'used' });
  server.use(http.post(URL, () => new HttpResponse(null, { status: 401 })));
  await expect(refreshSession()).resolves.toBeNull();
  expect(vi.mocked(createSession)).not.toHaveBeenCalled();
});
