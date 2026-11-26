import { expect, test } from '@playwright/test';

import { adminAccount } from './support/admin-account';

// The /api proxy of the admin on the real Java API (PCAR-6, QA fail of round 2). A browser sends the
// Origin header with every POST, PUT and DELETE, a same-origin one included, and the API refuses a
// cross-origin request from an origin it does not list (403 "Invalid CORS request"). The admin calls
// the API on /api of its own origin, so the API must take such a request as same-origin on every
// environment, behind the TLS of the VPS too, with no origin listed for it. The request fixture sends
// no Origin of its own: each test sends the one a browser on the admin sends.

const SIGN_IN = '/api/auth/tokens';

test.describe('the /api proxy of the admin', () => {
  test('passes a request with the Origin of the admin to the API, which answers a wrong sign-in with 401', async ({
    request,
    baseURL,
  }) => {
    const origin = new URL(baseURL ?? '').origin;

    const response = await request.post(SIGN_IN, {
      headers: { Origin: origin },
      data: { grantType: 'password', email: 'nobody@example.com', password: 'not-the-password-1' },
    });

    expect(response.status(), await response.text()).toBe(401);
  });

  test('signs the admin in through /api with the Origin of the admin', async ({
    request,
    baseURL,
  }) => {
    const account = adminAccount(baseURL);
    test.skip(!account.ok, account.ok ? '' : account.reason); // reason: only the admin of where it runs can sign in
    if (!account.ok) return;
    const origin = new URL(baseURL ?? '').origin;

    const response = await request.post(SIGN_IN, {
      headers: { Origin: origin },
      data: { grantType: 'password', ...account.credentials },
    });

    expect(response.status()).toBe(200);
    expect(await response.json()).toHaveProperty('accessToken');
  });
});
