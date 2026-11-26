import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it } from 'vitest';

import { clearTokens, getAccessToken, getRefreshToken, setTokens } from '@/lib/auth/token-storage';
import { server } from '@/testing/msw/server';

import { logout, useLogout } from './logout';

// POST /api/auth/revocations (204) revokes the refresh token on the API; the tokens of this tab
// are cleared whatever the API answers, so signing out never fails on the user.
function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe('logout', () => {
  afterEach(() => {
    clearTokens();
  });

  it('sends the refresh token to POST /auth/revocations', async () => {
    setTokens({ accessToken: 'access', refreshToken: 'refresh-to-revoke' });
    let body: unknown = null;
    server.use(
      http.post('*/auth/revocations', async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await logout();

    expect(body).toEqual({ refreshToken: 'refresh-to-revoke' });
  });

  it('clears the tokens even when the API fails', async () => {
    setTokens({ accessToken: 'access', refreshToken: 'refresh' });
    server.use(http.post('*/auth/revocations', () => new HttpResponse(null, { status: 503 })));
    const { result } = renderHook(() => useLogout(), { wrapper });

    act(() => {
      result.current.mutate();
    });

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });
});
