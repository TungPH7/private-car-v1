import { z } from 'zod';

/**
 * Model form HỒ SƠ (folder 16) — schema thuần, SHARE client (RHF + zodResolver) ↔ server (Server Action
 * re-validate). Khớp `UpdateMeBodySchema` backend (`{ name, phoneNumber, avatar }`). Phát CODE trung tính
 * (`{ error: 'code' }`) → UI map `t('Validation.<code>')` (kỷ luật folder 13).
 */
/** Same rule as `UpdateProfileRequest.phoneNumber` of the Java API: digits and separators, optional +. */
const PHONE_PATTERN = /^\+?[0-9 ().-]{6,31}$/;
/** The Java API accepts only https avatars (no javascript:, data: or plain http). */
const AVATAR_PROTOCOL = 'https:';
/** BCrypt of the API reads 72 bytes at most; it asks for 8 characters at least. */
const NEW_PASSWORD_MIN_LENGTH = 8;
const NEW_PASSWORD_MAX_LENGTH = 72;

export const updateProfileSchema = z.object({
  name: z.string().min(1, { error: 'required' }).max(100, { error: 'too_long' }),
  // Empty, or the phone rule of the API. `.refine` gives one clean code (a union muddles codes).
  phoneNumber: z.string().refine((v) => v === '' || PHONE_PATTERN.test(v), {
    error: 'invalid_phone',
  }),
  // Empty, or an https URL. `URL.canParse` exists in Node 24 and browsers.
  avatar: z
    .string()
    .refine((v) => v === '' || (URL.canParse(v) && new URL(v).protocol === AVATAR_PROTOCOL), {
      error: 'invalid_url',
    }),
});
export type UpdateProfileInput = z.infer<typeof updateProfileSchema>;

/**
 * Response of `PUT /api/users/me` = the profile. Validated **minimally** at the boundary: it only
 * confirms the update (folder 11).
 */
export const updateProfileResSchema = z.object({
  id: z.string(),
  name: z.string(),
});

/**
 * Password change — the DAL sends `{ currentPassword: password, newPassword }` to
 * `PUT /api/users/me/password` (new password 8–72 characters); `confirmNewPassword` stays in the
 * form (superRefine `newPassword === confirmNewPassword`).
 *
 * ⭐ `superRefine` = validate LIÊN-TRƯỜNG (cross-field): zodResolver chạy nó Ở CLIENT → lệch confirm
 * hiện lỗi TỨC THÌ (bài học chính của Stage B; field-level rule thường không bắt được quan-hệ-2-field).
 * Issue đặt `path: ['confirmNewPassword']` + `message: 'password_mismatch'` (code) → gắn lỗi đúng field.
 */
export const changePasswordSchema = z
  .object({
    password: z.string().min(1, { error: 'required' }),
    newPassword: z
      .string()
      .min(NEW_PASSWORD_MIN_LENGTH, { error: 'password_too_short' })
      .max(NEW_PASSWORD_MAX_LENGTH, { error: 'too_long' }),
    confirmNewPassword: z.string().min(1, { error: 'required' }),
  })
  .superRefine((data, ctx) => {
    if (data.newPassword !== data.confirmNewPassword) {
      ctx.addIssue({ code: 'custom', path: ['confirmNewPassword'], message: 'password_mismatch' });
    }
  });
export type ChangePasswordInput = z.infer<typeof changePasswordSchema>;

/**
 * Kết quả các action form hồ sơ trả về (DÙNG CHUNG `updateProfileAction` + `changePasswordAction`) —
 * RHF đọc để hiện lỗi field / lỗi form / thành công. KHÔNG có `idle`: form RHF không dùng state-machine
 * `useActionState` (folder 15) — nó gọi action rồi rẽ nhánh theo kết quả (idle thừa sẽ phá narrowing).
 */
export type ProfileFormState =
  | { status: 'success' }
  | { status: 'error'; reason: 'validation'; fieldErrors: Record<string, string> }
  | { status: 'error'; reason: 'auth' | 'unknown' };
