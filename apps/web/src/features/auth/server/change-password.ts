import 'server-only';

import { type ChangePasswordInput } from '~/features/auth/model/profile-form';
import { AUTH_ENDPOINTS } from '~/lib/auth/api-endpoints';
import { authHeaders } from '~/lib/auth/auth-headers';
import { httpSendNoContent } from '~/lib/http/client';

/**
 * Password change DAL (BFF) — `PUT /api/users/me/password` (Bearer) → 204. The API checks the
 * current password and revokes every refresh token of the account. `server-only`.
 */
export async function changePassword(input: ChangePasswordInput): Promise<void> {
  await httpSendNoContent('PUT', AUTH_ENDPOINTS.password, {
    headers: { 'Content-Type': 'application/json', ...(await authHeaders()) },
    body: JSON.stringify({ currentPassword: input.password, newPassword: input.newPassword }),
  });
}
