import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';

import { Can } from '@/features/auth/components/can';
import { clearTokens, setTokens } from '@/lib/auth/token-storage';
import { ADMIN_PROFILE } from '@/testing/api-fixtures';
import { server } from '@/testing/msw/server';
import { renderWithProviders, screen } from '@/testing/render';

describe('Can (element-level RBAC)', () => {
  afterEach(() => {
    clearTokens();
  });

  it('hides children when the user lacks the permission', () => {
    // No token → profile query disabled → no permissions → children hidden.
    renderWithProviders(
      <Can method="DELETE" path="/api/products/{id}">
        <button>Delete</button>
      </Can>,
    );
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
  });

  it('shows children when the profile grants the permission', async () => {
    // GET /users/me of an ADMIN of the API: product:delete is DELETE /api/products/{id}.
    server.use(http.get('*/users/me', () => HttpResponse.json(ADMIN_PROFILE)));
    setTokens({ accessToken: 'a', refreshToken: 'r' }); // before render → profile enabled

    renderWithProviders(
      <Can method="DELETE" path="/api/products/{id}">
        <button>Delete</button>
      </Can>,
    );

    expect(await screen.findByRole('button', { name: 'Delete' })).toBeInTheDocument();
  });
});
