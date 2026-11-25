import { z } from 'zod';

/**
 * Minimal DTO of `GET /api/users/me` of the Java API (PCAR-4): validate at the boundary and keep
 * only the fields the app uses. Zod objects are non-strict, so extra fields (the permission
 * `name`, …) are dropped and the DTO survives drift. Enough for display and RBAC: id, email,
 * name, phone and avatar for the profile form, role name and permissions {module, path, method}.
 */
const permissionSchema = z.object({
  module: z.string(),
  path: z.string(),
  method: z.string(),
});

export const profileSchema = z.object({
  /** The account UUID, the `sub` and `userId` of the access token. */
  id: z.string().min(1),
  email: z.email(),
  name: z.string(),
  // The API sends null when there is no phone; the profile form edits a string.
  phoneNumber: z
    .string()
    .nullable()
    .transform((value) => value ?? ''),
  avatar: z.string().nullable(),
  role: z.object({
    name: z.string(),
    permissions: z.array(permissionSchema),
  }),
});

export type Permission = z.infer<typeof permissionSchema>;
export type AuthUser = z.infer<typeof profileSchema>;
