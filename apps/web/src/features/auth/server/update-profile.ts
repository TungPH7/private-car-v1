import 'server-only';

import {
  updateProfileResSchema,
  type UpdateProfileInput,
} from '~/features/auth/model/profile-form';
import { AUTH_ENDPOINTS } from '~/lib/auth/api-endpoints';
import { authHeaders } from '~/lib/auth/auth-headers';
import { httpPut } from '~/lib/http/client';

/**
 * Profile update DAL (BFF) — `PUT /api/users/me` (Bearer): the API takes the account from the
 * token, so a user edits only their own profile. `server-only`. The form keeps empty strings; the
 * API wants null for "no phone" and "no avatar".
 */
export async function updateProfile(
  input: UpdateProfileInput,
): Promise<{ id: string; name: string }> {
  return httpPut(AUTH_ENDPOINTS.me, updateProfileResSchema, {
    headers: { 'Content-Type': 'application/json', ...(await authHeaders()) },
    body: JSON.stringify({
      name: input.name,
      phoneNumber: emptyToNull(input.phoneNumber),
      avatar: emptyToNull(input.avatar),
    }),
  });
}

function emptyToNull(value: string): string | null {
  return value.trim() === '' ? null : value;
}
