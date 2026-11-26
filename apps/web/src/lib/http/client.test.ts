// @vitest-environment node
// client.ts đọc `env.API_URL` (biến SERVER). jsdom có `window` → t3-env tưởng CLIENT → đọc
// server-var sẽ THROW → chạy test ở `node` (không window) như env.test.ts (bài học folder 07).
import { http, HttpResponse } from 'msw';
import { expect, test } from 'vitest';
import { z } from 'zod';

import { ApiError } from '~/lib/http/api-error';
import { httpDelete, httpGet, httpSendNoContent } from '~/lib/http/client';
import { server } from '~/testing/msw/server';

// `httpGet` đọc `env.API_URL` = 'https://api.example.test' trong Vitest (folder 07 `test.env`).
// MSW chặn fetch ở Node → test boundary thật (fetch → status → JSON → Zod → ApiError).
// (`import 'server-only'` trong client.ts được alias về stub no-op — xem vitest.config.)
const BASE = 'https://api.example.test';
const schema = z.object({ ok: z.boolean() });
const msgSchema = z.object({ message: z.string() });

test('GET hợp lệ → trả data đã validate', async () => {
  server.use(http.get(`${BASE}/ping`, () => HttpResponse.json({ ok: true })));
  await expect(httpGet('/ping', schema)).resolves.toEqual({ ok: true });
});

test('status không-2xx → ApiError kind=http + status', async () => {
  server.use(http.get(`${BASE}/boom`, () => new HttpResponse(null, { status: 500 })));
  await expect(httpGet('/boom', schema)).rejects.toBeInstanceOf(ApiError);
  await expect(httpGet('/boom', schema)).rejects.toMatchObject({ kind: 'http', status: 500 });
});

test('body sai shape → ApiError kind=validation', async () => {
  server.use(http.get(`${BASE}/bad`, () => HttpResponse.json({ ok: 'nope' })));
  await expect(httpGet('/bad', schema)).rejects.toMatchObject({ kind: 'validation' });
});

// --- DELETE (folder 12: dùng cho mutation qua DAL) -----------------------------
test('DELETE hợp lệ → trả message đã validate', async () => {
  server.use(http.delete(`${BASE}/items/1`, () => HttpResponse.json({ message: 'deleted' })));
  await expect(httpDelete('/items/1', msgSchema)).resolves.toEqual({ message: 'deleted' });
});

test('DELETE 401 → ApiError kind=http status=401 (isAuthError)', async () => {
  server.use(http.delete(`${BASE}/items/1`, () => new HttpResponse(null, { status: 401 })));
  await expect(httpDelete('/items/1', msgSchema)).rejects.toMatchObject({
    kind: 'http',
    status: 401,
  });
});

test('forward init.headers (auth seam folder 15) xuống fetch', async () => {
  let authHeader: string | null = null;
  server.use(
    http.delete(`${BASE}/items/1`, ({ request }) => {
      authHeader = request.headers.get('authorization');
      return HttpResponse.json({ message: 'ok' });
    }),
  );
  await httpDelete('/items/1', msgSchema, { headers: { Authorization: 'Bearer t' } });
  expect(authHeader).toBe('Bearer t');
});

// --- No content (204): sign out, password change, delete of the Java API ------------------
test('a 204 resolves with nothing and sends the method, headers and body', async () => {
  let seen: { method: string; auth: string | null; body: string } | null = null;
  server.use(
    http.post(`${BASE}/api/auth/revocations`, async ({ request }) => {
      seen = {
        method: request.method,
        auth: request.headers.get('authorization'),
        body: await request.text(),
      };
      return new HttpResponse(null, { status: 204 });
    }),
  );

  await expect(
    httpSendNoContent('POST', '/api/auth/revocations', {
      headers: { Authorization: 'Bearer t', 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: 'r' }),
    }),
  ).resolves.toBeUndefined();
  expect(seen).toEqual({ method: 'POST', auth: 'Bearer t', body: '{"refreshToken":"r"}' });
});

test('a non-2xx answer to a no-content request is an ApiError with its status', async () => {
  server.use(http.delete(`${BASE}/api/products/x`, () => new HttpResponse(null, { status: 403 })));
  await expect(httpSendNoContent('DELETE', '/api/products/x')).rejects.toMatchObject({
    kind: 'http',
    status: 403,
  });
});

test('a no-content request that cannot reach the server is a network ApiError', async () => {
  server.use(http.put(`${BASE}/api/users/me/password`, () => HttpResponse.error()));
  await expect(httpSendNoContent('PUT', '/api/users/me/password')).rejects.toMatchObject({
    kind: 'network',
  });
});
