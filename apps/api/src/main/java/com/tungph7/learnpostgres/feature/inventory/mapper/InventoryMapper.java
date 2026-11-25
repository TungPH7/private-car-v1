package com.tungph7.learnpostgres.feature.inventory.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.tungph7.learnpostgres.feature.inventory.domain.InventoryMovement;
import com.tungph7.learnpostgres.feature.inventory.domain.ProductInventory;
import com.tungph7.learnpostgres.feature.inventory.dto.InventoryResponse;
import com.tungph7.learnpostgres.feature.inventory.dto.MovementResponse;

/**
 * Entity → response DTO.
 *
 * <p>{@code componentModel} không khai ở đây vì pom đã set {@code
 * -Amapstruct.defaultComponentModel=spring} cho toàn project, nên {@code @Mapper} trần vẫn sinh ra
 * Spring bean. Pom cũng bật {@code unmappedTargetPolicy=ERROR}: thiếu map một field nào của record
 * đích là compile fail, không phải null lặng lẽ.
 */
@Mapper
public interface InventoryMapper {

  /** {@code available} là giá trị tính, không có cột trong DB — lấy từ getter của entity. */
  @Mapping(target = "available", expression = "java(inventory.getAvailable())")
  InventoryResponse toResponse(ProductInventory inventory);

  /** Enum → String để response JSON ổn định, không phụ thuộc cách serialize enum. */
  @Mapping(target = "movementType", expression = "java(movement.getMovementType().name())")
  MovementResponse toMovementResponse(InventoryMovement movement);
}
