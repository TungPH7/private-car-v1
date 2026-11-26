import { useMutation, useQueryClient } from '@tanstack/react-query';

import { authKeys } from '@/features/auth/api/auth-keys';
import {
  authTokensSchema,
  type AuthTokens,
  type LoginCredentials,
} from '@/features/auth/model/auth';
import { api } from '@/lib/api/api';
import { API_ENDPOINTS, GRANT_TYPE } from '@/lib/api/endpoints';
import { setTokens } from '@/lib/auth/token-storage';

// POST /auth/tokens (password grant) → tokens only (no user). The user arrives via GET /users/me,
// which the profile query refetches once tokens are stored.
export function login(credentials: LoginCredentials): Promise<AuthTokens> {
  return api.post(
    API_ENDPOINTS.tokens,
    { grantType: GRANT_TYPE.password, ...credentials },
    { schema: authTokensSchema },
  );
}

export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: login,
    onSuccess: (tokens) => {
      // Persist → useAccessToken flips → the (now enabled) profile query fetches.
      setTokens(tokens);
      void queryClient.invalidateQueries({ queryKey: authKeys.profile() });
    },
  });
}
