package com.tungph7.learnpostgres.feature.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Chỉnh tay sau kiểm kê. Hai delta KHÔNG validate dấu ở tầng DTO — âm là hợp lệ.
 *
 * <p>Chặn cuối là 5 CHECK constraint của V8: nếu delta đẩy on_hand/reserved xuống âm hoặc reserved
 * vượt on_hand thì DB reject và handler trả 409/400 tương ứng.
 */
public record AdjustRequest(
    int deltaOnHand,
    int deltaReserved,
    @NotBlank @Size(max = 255) String reason,
    @NotBlank @Size(max = 64) String actor) {}
