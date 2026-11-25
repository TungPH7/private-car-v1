package com.tungph7.learnpostgres.feature.coupon.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.tungph7.learnpostgres.feature.coupon.domain.Coupon;
import com.tungph7.learnpostgres.feature.coupon.dto.CouponResponse;

/**
 * Entity → response DTO.
 *
 * <p>{@code componentModel} không khai ở đây vì pom đã set {@code
 * -Amapstruct.defaultComponentModel=spring} cho toàn project. Pom cũng bật {@code
 * unmappedTargetPolicy=ERROR}: thiếu map một field nào của record đích là compile fail, không phải
 * null lặng lẽ.
 *
 * <p>{@code validFrom} / {@code validTo} KHÔNG cần {@code @Mapping}: entity đã có sẵn getter cùng
 * tên ({@code Coupon.getValidFrom()} bóc biên ra khỏi {@code TstzRange}), MapStruct tự khớp. Nhờ
 * vậy DTO không phải biết cột dưới DB là một kiểu range.
 */
@Mapper
public interface CouponMapper {

  /**
   * Enum → String để response JSON ổn định, không phụ thuộc cách serialize enum của Jackson.
   *
   * <p>Cùng lý do {@code InventoryMapper} và {@code CustomerMapper} đang làm.
   */
  @Mapping(target = "type", expression = "java(coupon.getType().name())")
  CouponResponse toResponse(Coupon coupon);
}
