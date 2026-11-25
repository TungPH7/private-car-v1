package com.tungph7.learnpostgres.feature.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.tungph7.learnpostgres.common.error.BusinessException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.feature.inventory.domain.ProductInventory;
import com.tungph7.learnpostgres.feature.inventory.repository.InventoryRepository;
import com.tungph7.learnpostgres.feature.inventory.repository.MovementRepository;
import com.tungph7.learnpostgres.feature.inventory.repository.projection.InventorySnapshot;
import com.tungph7.learnpostgres.feature.inventory.service.InventoryService.ReserveItem;

/**
 * Unit test thuần mock — kiểm phần rẽ nhánh của service, không chạm DB.
 *
 * <p>Hành vi atomic thật (2 request tranh nhau) KHÔNG kiểm được ở đây vì mock luôn trả cái ta bảo
 * nó trả. Phần đó nằm ở {@code InventoryConcurrencyIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

  @Mock InventoryRepository inventoryRepo;
  @Mock MovementRepository movementRepo;

  // BẮT BUỘC phải mock: InventoryServiceImpl nhận 3 tham số constructor. Thiếu mock này,
  // Mockito inject null và reloadAfterWrite() sẽ NPE ngay ở test đầu tiên.
  @Mock EntityManager entityManager;

  @InjectMocks InventoryServiceImpl service;

  private UUID productId;

  @BeforeEach
  void setUp() {
    productId = UUID.randomUUID();
  }

  @Test
  @DisplayName("reserve — đủ stock thì ghi đúng 1 movement")
  void reserveSuccess() {
    when(inventoryRepo.reserve(productId, 30)).thenReturn(List.of(snapshot(70, 30)));
    when(inventoryRepo.findById(productId))
        .thenReturn(Optional.of(new ProductInventory(productId)));

    service.reserve(productId, 30, UUID.randomUUID(), "system");

    verify(movementRepo, times(1)).save(any());
  }

  @Test
  @DisplayName("reserve — thiếu stock thì ném INV_INSUFFICIENT_STOCK và KHÔNG ghi movement")
  void reserveInsufficient() {
    when(inventoryRepo.reserve(productId, 100)).thenReturn(List.of());
    when(inventoryRepo.existsById(productId)).thenReturn(true);

    assertThatThrownBy(() -> service.reserve(productId, 100, null, "system"))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.INV_INSUFFICIENT_STOCK);

    verify(movementRepo, never()).save(any());
  }

  @Test
  @DisplayName("reserve — chưa init inventory thì ném NotFound, phân biệt với hết hàng")
  void reserveNotFound() {
    when(inventoryRepo.reserve(productId, 30)).thenReturn(List.of());
    when(inventoryRepo.existsById(productId)).thenReturn(false);

    assertThatThrownBy(() -> service.reserve(productId, 30, null, "system"))
        .isInstanceOf(NotFoundException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.INV_NOT_FOUND);
  }

  @Test
  @DisplayName("reserve — qty <= 0 chặn ngay, không đụng tới repository")
  void reserveNegative() {
    assertThatThrownBy(() -> service.reserve(productId, 0, null, "system"))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.INV_NEGATIVE_QUANTITY);

    verify(inventoryRepo, never()).reserve(any(), anyInt());
  }

  @Test
  @DisplayName("release — vượt reserved thì ném INV_RELEASE_INVALID")
  void releaseInvalid() {
    when(inventoryRepo.release(productId, 50)).thenReturn(List.of());
    when(inventoryRepo.existsById(productId)).thenReturn(true);

    assertThatThrownBy(() -> service.release(productId, 50, null, "system"))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.INV_RELEASE_INVALID);
  }

  @Test
  @DisplayName("commit — on_hand hoặc reserved không đủ thì ném INV_INSUFFICIENT_STOCK")
  void commitInvalid() {
    when(inventoryRepo.commit(productId, 30)).thenReturn(List.of());
    when(inventoryRepo.existsById(productId)).thenReturn(true);

    assertThatThrownBy(() -> service.commit(productId, 30, null, "system"))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(ErrorCode.INV_INSUFFICIENT_STOCK);
  }

  @Test
  @DisplayName("bulkReserve — reserve theo productId tăng dần, bất kể thứ tự đầu vào")
  void bulkReserveSortsItems() {
    UUID p1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID p2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID p3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    List<ReserveItem> items =
        List.of(new ReserveItem(p3, 1), new ReserveItem(p1, 2), new ReserveItem(p2, 3));

    when(inventoryRepo.reserve(any(), anyInt())).thenReturn(List.of(snapshot(10, 5)));
    when(inventoryRepo.findById(any())).thenReturn(Optional.of(new ProductInventory(p1)));

    service.bulkReserve(items, UUID.randomUUID(), "test");

    // Mockito ghi lại theo đúng thứ tự gọi -> phải là p1, p2, p3.
    // Đây chính là thứ chặn deadlock: mọi transaction khoá row theo cùng một thứ tự.
    ArgumentCaptor<UUID> order = ArgumentCaptor.forClass(UUID.class);
    verify(inventoryRepo, times(3)).reserve(order.capture(), anyInt());
    assertThat(order.getAllValues()).containsExactly(p1, p2, p3);
  }

  private static InventorySnapshot snapshot(int onHand, int reserved) {
    return new InventorySnapshot() {
      @Override
      public int getOnHand() {
        return onHand;
      }

      @Override
      public int getReserved() {
        return reserved;
      }
    };
  }
}
