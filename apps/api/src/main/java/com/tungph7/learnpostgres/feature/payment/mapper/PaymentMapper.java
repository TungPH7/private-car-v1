package com.tungph7.learnpostgres.feature.payment.mapper;

import org.mapstruct.Mapper;

import com.tungph7.learnpostgres.feature.payment.domain.Payment;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentRefund;
import com.tungph7.learnpostgres.feature.payment.dto.PaymentResponse;
import com.tungph7.learnpostgres.feature.payment.dto.RefundResponse;

/**
 * Entity → response DTO của payment và refund.
 *
 * <p><b>Không có {@code @Mapping} nào</b> — cùng lý do {@code OrderMapper} (F05): mọi field của 2
 * record đích có getter cùng tên ở entity ({@code id}, {@code createdAt} của payment nằm ở {@code
 * BaseEntity}); 3 field enum ({@code status} của cả hai, {@code gateway}) sang {@code String} bằng
 * chuyển đổi có sẵn của MapStruct — {@code name()} bọc trong null-check. {@code
 * unmappedTargetPolicy=ERROR} ở pom: thiếu map một field của record đích là compile fail.
 *
 * <p>Field của entity không có trong DTO ({@code gatewayResponse}, {@code version}, ...) bị bỏ qua
 * im lặng — {@code unmappedSourcePolicy} mặc định là IGNORE.
 *
 * <p>{@code PaymentIntentResult} / {@code RefundResult} / 2 kết quả webhook không đi qua đây:
 * service dựng sẵn (bài 03 §3).
 */
@Mapper
public interface PaymentMapper {

  PaymentResponse toResponse(Payment payment);

  RefundResponse toRefundResponse(PaymentRefund refund);
}
