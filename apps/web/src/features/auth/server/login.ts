import 'server-only';

import { loginResponseSchema, type LoginInput } from '~/features/auth/model/auth';
import { AUTH_ENDPOINTS, GRANT_TYPE } from '~/lib/auth/api-endpoints';
import { type SessionTokens } from '~/lib/auth/session-seal';
import { httpPost } from '~/lib/http/client';

/**
 * Sign-in DAL (BFF) — `POST /api/auth/tokens` with the password grant (public) →
 * `{ accessToken, refreshToken }`. `server-only`. The response is validated at the boundary; the
 * DAL sets no cookie: the Server Action calls `createSession` with the tokens.
 */
export function login(input: LoginInput): Promise<SessionTokens> {
  return httpPost(AUTH_ENDPOINTS.tokens, loginResponseSchema, {
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ grantType: GRANT_TYPE.password, ...input }),
  });
}
