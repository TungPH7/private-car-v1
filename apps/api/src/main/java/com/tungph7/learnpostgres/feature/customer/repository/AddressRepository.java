package com.tungph7.learnpostgres.feature.customer.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.feature.customer.domain.Address;
import com.tungph7.learnpostgres.feature.customer.domain.AddressType;

@Repository
public interface AddressRepository extends JpaRepository<Address, UUID> {

  /** List addresses (non-deleted) của customer. */
  List<Address> findByCustomerIdAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(UUID customerId);

  /** Filter by type. */
  List<Address> findByCustomerIdAndTypeAndDeletedFalseOrderByIsDefaultDescCreatedAtDesc(
      UUID customerId, AddressType type);

  /** Lấy default address (nếu có). */
  Optional<Address> findFirstByCustomerIdAndTypeAndIsDefaultTrueAndDeletedFalse(
      UUID customerId, AddressType type);

  /**
   * Chuyển default sang {@code newDefaultId} — 2 statement, BẮT BUỘC theo thứ tự clear → set.
   *
   * <p>Không gộp được thành 1 UPDATE `SET is_default = (id = :newDefaultId)`: partial unique index
   * `uq_addresses_default_per_type` được check theo TỪNG ROW trong lúc UPDATE chứ không hoãn tới
   * cuối statement, và index partial thì không khai báo DEFERRABLE được. Nếu executor chạm row
   * target trước row đang default → tồn tại 2 row TRUE trong tích tắc → duplicate key. Kết quả phụ
   * thuộc thứ tự vật lý (ctid) nên lúc chạy được lúc không — xem F02 QnA Q23.
   *
   * <p>Cả 2 statement nằm trong transaction của service nên vẫn all-or-nothing.
   *
   * @return 1 nếu set được default mới, 0 nếu address không tồn tại / không thuộc customer / đã bị
   *     xoá mềm — caller dựa vào đây để raise ADR_NOT_FOUND.
   */
  default int swapDefault(UUID customerId, UUID newDefaultId) {
    clearDefaultOfSameType(customerId, newDefaultId);
    return markAsDefault(customerId, newDefaultId);
  }

  /**
   * Bước 1: bỏ cờ default của address đang giữ default cho cùng (customer, type).
   *
   * <p>flushAutomatically: đẩy INSERT/UPDATE đang pending xuống DB TRƯỚC khi UPDATE này chạy — nếu
   * không, row vừa save có thể chưa tồn tại nên không nằm trong scope.
   *
   * <p>clearAutomatically: native UPDATE đi thẳng xuống DB, không qua persistence context → entity
   * đang managed vẫn giữ is_default cũ và findById() sẽ trả lại chính nó (1st-level cache) thay vì
   * đọc DB. Clear context sau UPDATE để lần đọc kế tiếp lấy giá trị thật.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE app.addresses
          SET is_default = FALSE
          WHERE customer_id = :customerId
            AND type = (SELECT type FROM app.addresses WHERE id = :newDefaultId)
            AND is_deleted = FALSE
            AND is_default = TRUE
            AND id <> :newDefaultId
          """,
      nativeQuery = true)
  int clearDefaultOfSameType(
      @Param("customerId") UUID customerId, @Param("newDefaultId") UUID newDefaultId);

  /** Bước 2: set default cho address mới. Chỉ chạy được sau khi bước 1 đã nhả cờ. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE app.addresses
          SET is_default = TRUE
          WHERE id = :newDefaultId
            AND customer_id = :customerId
            AND is_deleted = FALSE
          """,
      nativeQuery = true)
  int markAsDefault(@Param("customerId") UUID customerId, @Param("newDefaultId") UUID newDefaultId);

  /** Verify address thuộc customer (security check trước khi setDefault/update). */
  boolean existsByIdAndCustomerId(UUID id, UUID customerId);
}
