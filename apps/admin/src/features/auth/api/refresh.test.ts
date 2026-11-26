import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';

import { GRANT_TYPE } from '@/lib/api/endpoints';
import { clearTokens, getAccessToken, getRefreshToken, setTokens } from '@/lib/auth/token-storage';
import { apiTokenPair } from '@/testing/api-fixtures';
import { server } from '@/testing/msw/server';

import { refreshAuthTokens } from './refresh';

// POST /api/auth/tokens with the refresh_token grant: the API answers a new pair and revokes the
// old refresh token (single use, PCAR-4), so both tokens are replaced.
describe('refreshAuthTokens', () => {
  afterEach(() => {
    clearTokens();
  });

  it('trades the refresh token for a new pair on POST /auth/tokens', async () => {
    setTokens({ accessToken: 'old-access', refreshToken: 'old-refresh' });
    let body: unknown = null;
    server.use(
      http.post('*/auth/tokens', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(apiTokenPair('new-access', 'new-refresh'));
      }),
    );

    await refreshAuthTokens();

    expect(body).toEqual({ grantType: GRANT_TYPE.refreshToken, refreshToken: 'old-refresh' });
    expect(getAccessToken()).toBe('new-access');
    expect(getRefreshToken()).toBe('new-refresh');
  });

  it('fails without a call when there is no refresh token', async () => {
    let called = false;
    server.use(
      http.post('*/auth/tokens', () => {
        called = true;
        return HttpResponse.json(apiTokenPair());
      }),
    );

    await expect(refreshAuthTokens()).rejects.toThrow('No refresh token available');
    expect(called).toBe(false);
  });

  it('fails and keeps the old tokens when the API refuses the refresh token', async () => {
    setTokens({ accessToken: 'old-access', refreshToken: 'reused-refresh' });
    server.use(
      http.post('*/auth/tokens', () =>
        HttpResponse.json({ code: 'AUTH_INVALID_REFRESH_TOKEN' }, { status: 401 }),
      ),
    );

    await expect(refreshAuthTokens()).rejects.toThrow();
    expect(getRefreshToken()).toBe('reused-refresh');
  });
});
