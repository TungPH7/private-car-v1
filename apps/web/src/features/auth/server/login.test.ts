// @vitest-environment node
// The DAL reads env.API_URL (a server variable) and is server-only: jsdom has a window, so t3-env
// would take it for the client and throw. MSW intercepts fetch in Node.
import { http, HttpResponse } from 'msw';
import { expect, test } from 'vitest';

import { login } from '~/features/auth/server/login';
import { ApiError } from '~/lib/http/api-error';
import { API_BASE, apiTokenPair } from '~/testing/api-fixtures';
import { server } from '~/testing/msw/server';

const URL = `${API_BASE}/api/auth/tokens`;

test('signs in with the password grant and returns the token pair', async () => {
  let sent: unknown;
  server.use(
    http.post(URL, async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json(apiTokenPair('a.b.c', 'd-e-f'));
    }),
  );

  await expect(login({ email: 'a@b.com', password: 'secret' })).resolves.toEqual({
    accessToken: 'a.b.c',
    refreshToken: 'd-e-f',
  });
  expect(sent).toEqual({ grantType: 'password', email: 'a@b.com', password: 'secret' });
});

test('wrong credentials (401) → ApiError(http)', async () => {
  server.use(http.post(URL, () => new HttpResponse(null, { status: 401 })));
  await expect(login({ email: 'a@b.com', password: 'wrong' })).rejects.toBeInstanceOf(ApiError);
});

test('a response without the refresh token → ApiError(validation)', async () => {
  server.use(http.post(URL, () => HttpResponse.json({ accessToken: 'only-access' })));
  await expect(login({ email: 'a@b.com', password: 'secret' })).rejects.toMatchObject({
    kind: 'validation',
  });
});
