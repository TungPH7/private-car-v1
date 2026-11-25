#!/usr/bin/env bash
# Nạp .env vào CHÍNH shell hiện tại (khác load-env.sh — file đó là wrapper: nạp env rồi
# `exec` command kế tiếp trong tiến trình con).
#
# Usage:  source ./scripts/env.sh                 # dùng .env
#         source ./scripts/env.sh .env.staging    # chỉ định file khác
#         ENV_FILE=.env.staging source ./scripts/env.sh
#
# Cố ý KHÔNG dùng `set -euo pipefail` và KHÔNG dùng `exec`: cả hai sẽ giết terminal của bạn
# khi file này được `source`.

# -------------------- 1. Bắt lỗi chạy sai cách --------------------
# `./scripts/env.sh` chạy trong tiến trình con => biến mất ngay khi script kết thúc.
__env_sourced=0
if [ -n "${ZSH_EVAL_CONTEXT:-}" ]; then
    case "$ZSH_EVAL_CONTEXT" in *:file:*) __env_sourced=1 ;; esac
elif [ -n "${BASH_SOURCE:-}" ]; then
    [ "${BASH_SOURCE[0]}" != "$0" ] && __env_sourced=1
fi

if [ "$__env_sourced" -eq 0 ]; then
    echo "ERROR: file này phải được 'source', không phải chạy trực tiếp." >&2
    echo "       Chạy trực tiếp => biến chỉ sống trong tiến trình con rồi mất." >&2
    echo "  ->   source ./scripts/env.sh" >&2
    exit 1
fi
unset __env_sourced

# -------------------- 2. Xác định file env --------------------
__env_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
__env_root="$(cd "$__env_script_dir/.." && pwd)"
# Lưu ý: khi `source`, $1 kế thừa positional param của shell cha nếu bạn không truyền gì.
# Trong shell tương tác bình thường $1 rỗng nên an toàn; script nội bộ thì nên dùng ENV_FILE.
__env_file="${1:-${ENV_FILE:-.env}}"

# Cho phép source từ subfolder: không thấy ở cwd thì tìm ở repo root.
if [ ! -f "$__env_file" ] && [ -f "$__env_root/$__env_file" ]; then
    __env_file="$__env_root/$__env_file"
fi

if [ ! -f "$__env_file" ]; then
    echo "ERROR: không tìm thấy '$__env_file'. Copy từ template trước:" >&2
    echo "  cp .env.example .env" >&2
    unset __env_script_dir __env_root __env_file
    return 1
fi

# Kiểm tra cú pháp trước khi nạp — tránh .env hỏng làm bẩn shell đang dùng.
if ! bash -n "$__env_file" 2>/dev/null; then
    echo "ERROR: '$__env_file' sai cú pháp shell (thiếu dấu nháy? khoảng trắng quanh dấu '='?)." >&2
    bash -n "$__env_file"
    unset __env_script_dir __env_root __env_file
    return 1
fi

# -------------------- 3. Nạp & export --------------------
# `set -a` (allexport) => mọi biến gán trong .env đều tự động được export.
# Nhớ trạng thái cũ để không tắt nhầm nếu shell cha đang bật sẵn.
__env_had_allexport=0
case "$-" in *a*) __env_had_allexport=1 ;; esac

set -a
# shellcheck disable=SC1090
. "$__env_file"
__env_rc=$?
[ "$__env_had_allexport" -eq 1 ] || set +a

# -------------------- 4. Báo kết quả --------------------
if [ "$__env_rc" -ne 0 ]; then
    echo "ERROR: lỗi khi nạp '$__env_file' (exit $__env_rc)." >&2
else
    __env_count="$(grep -cE '^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*=' "$__env_file")"
    echo "OK: đã nạp $__env_count biến từ '$__env_file'"
    # In vài biến hay dùng để kiểm tra nhanh — KHÔNG in password.
    echo "    POSTGRES_DB=${POSTGRES_DB:-<unset>}  POSTGRES_USER=${POSTGRES_USER:-<unset>}"
    echo "    DB=${DB_HOST:-<unset>}:${DB_PORT:-<unset>}  SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-<unset>}"
fi

unset __env_script_dir __env_root __env_file __env_had_allexport __env_count
if [ "$__env_rc" -eq 0 ]; then
    unset __env_rc
    return 0
else
    unset __env_rc
    return 1
fi
