package com.tungph7.learnpostgres.feature.order.mapper;

import org.mapstruct.Mapper;

import com.tungph7.learnpostgres.feature.order.domain.Order;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatusHistory;
import com.tungph7.learnpostgres.feature.order.dto.OrderResponse;
import com.tungph7.learnpostgres.feature.order.dto.StatusHistoryResponse;

/**
 * Entity → response DTO của order.
 *
 * <p>{@code componentModel=spring} và {@code unmappedTargetPolicy=ERROR} đặt ở pom cho toàn
 * project: thiếu map một field của record đích là compile fail, không phải null lặng lẽ.
 *
 * <p><b>Không có {@code @Mapping} nào.</b> Mọi field của 2 record đích đều có getter cùng tên ở
 * entity ({@code id}, {@code createdAt}, {@code version} nằm ở {@code BaseEntity}). Ba field enum
 * ({@code status}, {@code fromStatus}, {@code toStatus}) sang {@code String} bằng chuyển đổi có sẵn
 * của MapStruct: {@code name()}, bọc trong null-check.
 *
 * <p>Null-check đó là lý do không viết {@code expression = "java(x.getFromStatus().name())"} như
 * {@code CouponMapper} làm với {@code type}. Expression đúng với enum luôn có giá trị, nhưng {@code
 * fromStatus} là {@code null} ở dòng khởi tạo {@code NULL → PENDING} của MỌI đơn — expression sẽ
 * NPE ngay phần tử đầu tiên của {@code GET /orders/{id}/history}.
 *
 * <p>{@code OrderDetailResponse} không đi qua đây: service dựng sẵn từ 1 query ({@code
 * OrderServiceImpl.getDetail}).
 */
@Mapper
public interface OrderMapper {

  OrderResponse toResponse(Order order);

  StatusHistoryResponse toHistoryResponse(OrderStatusHistory history);
}
