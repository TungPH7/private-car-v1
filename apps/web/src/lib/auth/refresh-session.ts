import 'server-only';

import { AUTH_ENDPOINTS, GRANT_TYPE } from '~/lib/auth/api-endpoints';
import { createSession, getSession } from '~/lib/auth/session';
import { sessionTokensSchema, type SessionTokens } from '~/lib/auth/session-seal';
import { httpPost } from '~/lib/http/client';

/**
 * Refresh ROTATION — `POST /api/auth/tokens` with the refresh_token grant. The API rotates: the old
 * refresh token is spent, and reusing it revokes every token of the account. Reads the refresh
 * token of the session → exchanges it for a new pair → RESEALS the cookie.
 *
 * ⭐⭐ CHỈ gọi được nơi SET được cookie: **Route Handler / Server Action** — KHÔNG phải lúc RSC render
 * (render không set được cookie). Vì vậy refresh sống ở Route Handler `/api/auth/refresh` (có thể gọi
 * thêm từ Server Action); còn RSC gặp access token hết hạn thì `getProfile`/guard trả null → redirect
 * /login (không tự refresh được). Đây là RÀNG BUỘC then chốt của mô hình cookie server-first.
 *
 * Trả cặp token mới, hoặc null nếu không có phiên / refresh thất bại (caller nên xoá phiên + về login).
 */
export async function refreshSession(): Promise<SessionTokens | null> {
  const session = await getSession();
  if (!session) return null;
  let next: SessionTokens;
  try {
    next = await httpPost(AUTH_ENDPOINTS.tokens, sessionTokensSchema, {
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        grantType: GRANT_TYPE.refreshToken,
        refreshToken: session.refreshToken,
      }),
    });
  } catch {
    return null; // refresh token hết hạn / đã dùng → caller xử lý (xoá phiên + login)
  }
  await createSession(next); // re-seal cookie (hợp lệ trong Route Handler / Server Action)
  return next;
}
