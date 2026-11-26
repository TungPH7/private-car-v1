import 'server-only';

import { AUTH_ENDPOINTS } from '~/lib/auth/api-endpoints';
import { type SessionTokens } from '~/lib/auth/session-seal';
import { httpSendNoContent } from '~/lib/http/client';

/**
 * Sign-out DAL (BFF) — `POST /api/auth/revocations` with `{ refreshToken }` → 204: the API
 * revokes that refresh token. `server-only`.
 *
 * Best effort: the Server Action deletes our cookie even when this call fails (the refresh token
 * may be expired or spent already), so the user can always sign out on the frontend.
 */
export function logout(tokens: SessionTokens): Promise<void> {
  return httpSendNoContent('POST', AUTH_ENDPOINTS.revocations, {
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: tokens.refreshToken }),
  });
}
