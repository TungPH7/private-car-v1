package com.tungph7.learnpostgres.feature.inventory.dto;

import java.util.UUID;

/**
 * Kết quả đối soát state hiện tại với tổng delta trong movement log.
 *
 * <p>{@code inSync = true} khi cả 3 drift đều bằng 0. Drift khác 0 nghĩa là có thay đổi tồn kho
 * không đi qua service (UPDATE thẳng vào DB) hoặc có bug quên ghi movement.
 */
public record ReconcileResponse(
    UUID productId,
    int onHand,
    long onHandCalc,
    long onHandDrift,
    int reserved,
    long reservedCalc,
    long reservedDrift,
    long sold,
    long soldCalc,
    long soldDrift,
    boolean inSync) {}
