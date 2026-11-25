#!/usr/bin/env bash
# Kiểm tra các block code "paste" trong study-guide doc còn khớp file nguồn thật.
#
# Vì sao cần: doc chép lại nội dung file để học viên paste. Sửa file mà quên sửa doc (hoặc
# ngược lại) thì doc thành bản cũ — học viên paste ra thứ khác với repo, và phần giải thích
# trong doc không còn mô tả cái đang thật sự chạy. Đã xảy ra một lần với V10 (partial index
# còn now()) nên từ đó check bằng script thay vì bằng mắt.
#
# Source of truth là FILE NGUỒN. Doc chỉ chép lại.
#
# Usage:  ./scripts/check-doc-sync.sh          # chỉ kiểm tra; exit 1 nếu lệch
#         ./scripts/check-doc-sync.sh --fix    # chép file nguồn đè vào block trong doc
#
# Thêm cặp mới vào PAIRS theo format:
#   "<doc>|<heading mở>|<heading đóng>|<file nguồn>|<lang fence>|<block thứ mấy>"
set -euo pipefail

cd "$(dirname "$0")/.."

F01_DOC="study-guide-impl/implementation/tasks/impl-details/F01__catalog"
F02_DOC="study-guide-impl/implementation/tasks/impl-details/F02__customer"
F04_DOC="study-guide-impl/implementation/tasks/impl-details/F04__coupon"
F05_DOC="study-guide-impl/implementation/tasks/impl-details/F05__order"
F06_DOC="study-guide-impl/implementation/tasks/impl-details/F06__payment"
F07_DOC="study-guide-impl/implementation/tasks/impl-details/F07__dashboard"
F08_DOC="study-guide-impl/implementation/tasks/impl-details/F08__search-performance"
MIG="src/main/resources/db/migration"
JAVA="src/main/java/com/tungph7/learnpostgres"
COUPON="$JAVA/feature/coupon"
ORDER="$JAVA/feature/order"
PAYMENT="$JAVA/feature/payment"
DASHBOARD="$JAVA/feature/dashboard"
SEARCH="$JAVA/feature/search"
ADMIN="$JAVA/feature/admin"
TEST_JAVA="src/test/java/com/tungph7/learnpostgres"
TEST_COUPON="$TEST_JAVA/feature/coupon"
TEST_ORDER="$TEST_JAVA/feature/order"
TEST_PAYMENT="$TEST_JAVA/feature/payment"
TEST_DASHBOARD="$TEST_JAVA/feature/dashboard"

PAIRS=(
    # ===== F01 =====
    # Chỉ file test F05 bài 03 bổ sung (build clean lộ coverage thiếu). Các block F01 khác chưa đưa vào.
    "$F01_DOC/05-tests.md|### 2.1.|### 2.2.|$TEST_JAVA/feature/catalog/service/CategoryServiceImplTest.java|java|1"

    # ===== F02 =====
    # Chỉ 3 file bị F05 sửa lại (findProfile đọc orders thật). Các block F02 khác chưa đưa vào.
    "$F02_DOC/02-domain-and-repository.md|### 5.1.|### 5.2.|$JAVA/feature/customer/repository/CustomerRepository.java|java|1"
    "$F02_DOC/05-tests.md|## 3. Slice test|### 3.1.|$TEST_JAVA/feature/customer/repository/CustomerRepositoryTest.java|java|1"
    "$F02_DOC/05-tests.md|## 5. Integration test|### 5.1.|$TEST_JAVA/feature/customer/integration/CustomerIntegrationTest.java|java|1"

    # ===== F04 =====
    # --- 01-migration.md ---
    "$F04_DOC/01-migration.md|### 1.2.|### 1.3.|$MIG/V10__create_coupons_table.sql|sql|1"
    "$F04_DOC/01-migration.md|### 2.2.|## 3.|$MIG/V11__create_coupon_redemptions_table.sql|sql|1"

    # --- 02-domain-and-repository.md ---
    "$F04_DOC/02-domain-and-repository.md|### 2.2.|### 2.3.|$JAVA/common/domain/TstzRange.java|java|1"
    "$F04_DOC/02-domain-and-repository.md|### 2.3.|### 2.4.|$JAVA/common/domain/TstzRangeType.java|java|1"
    "$F04_DOC/02-domain-and-repository.md|## 3. Enum|## 4. Entity|$COUPON/domain/CouponType.java|java|1"
    "$F04_DOC/02-domain-and-repository.md|## 4. Entity|### 4.1.|$COUPON/domain/Coupon.java|java|1"
    "$F04_DOC/02-domain-and-repository.md|## 5. Entity|## 6. Repository|$COUPON/domain/CouponRedemption.java|java|1"
    "$F04_DOC/02-domain-and-repository.md|## 6. Repository|### 6.1.|$COUPON/repository/CouponRepository.java|java|1"
    "$F04_DOC/02-domain-and-repository.md|## 7. Repository|### 7.1.|$COUPON/repository/RedemptionRepository.java|java|1"

    # --- 03-service-and-error-codes.md ---
    "$F04_DOC/03-service-and-error-codes.md|### 3.1.|### 3.2.|$COUPON/dto/CreateCouponRequest.java|java|1"
    "$F04_DOC/03-service-and-error-codes.md|### 3.2.|### 3.3.|$COUPON/dto/ValidateCouponRequest.java|java|1"
    "$F04_DOC/03-service-and-error-codes.md|### 3.3.|### 3.4.|$COUPON/dto/ValidateCouponResponse.java|java|1"
    "$F04_DOC/03-service-and-error-codes.md|### 3.4.|### 3.5.|$COUPON/dto/CouponResponse.java|java|1"
    "$F04_DOC/03-service-and-error-codes.md|### 3.5.|## 4. |$COUPON/dto/RedemptionResponse.java|java|1"
    "$F04_DOC/03-service-and-error-codes.md|## 4. |## 5. |$COUPON/service/CouponService.java|java|1"
    "$F04_DOC/03-service-and-error-codes.md|## 5. |### 5.1.|$COUPON/service/CouponServiceImpl.java|java|1"

    # --- 04-dto-mapper-controller.md ---
    "$F04_DOC/04-dto-mapper-controller.md|## 1. Mapper|### 1.1.|$COUPON/mapper/CouponMapper.java|java|1"
    "$F04_DOC/04-dto-mapper-controller.md|## 2. Controller|### 2.1.|$COUPON/controller/CouponController.java|java|1"

    # --- 05-tests.md ---
    "$F04_DOC/05-tests.md|### 3.2.|### 3.3.|$TEST_COUPON/integration/CouponConcurrencyIntegrationTest.java|java|1"
    "$F04_DOC/05-tests.md|### 4.2.|### 4.3.|$TEST_COUPON/integration/CouponControllerIntegrationTest.java|java|1"

    # --- 06-verify-and-pr.md ---
    # Backtick phải escape: mỗi phần tử nằm trong "..." nên `x` sẽ bị shell hiểu là
    # command substitution và chạy thật.
    "$F04_DOC/06-verify-and-pr.md|Nội dung \`pr-body.md\`:|> Dùng \`--body-file\`|$F04_DOC/pr-body.md|markdown|1"

    # ===== F05 =====
    # --- 01-migration.md ---
    "$F05_DOC/01-migration.md|### 1.2.|### 1.3.|$MIG/V12__create_orders_table.sql|sql|1"
    "$F05_DOC/01-migration.md|### 2.2.|### 2.3.|$MIG/V13__create_order_items_table.sql|sql|1"
    "$F05_DOC/01-migration.md|### 3.2.|### 3.3.|$MIG/V14__create_order_status_history_table.sql|sql|1"
    "$F05_DOC/01-migration.md|### 4.2.|### 4.3.|$TEST_ORDER/OrderFixtures.java|java|1"

    # --- 02-domain-and-repository.md ---
    "$F05_DOC/02-domain-and-repository.md|## 2. Enum|## 3. |$ORDER/domain/OrderStatus.java|java|1"
    "$F05_DOC/02-domain-and-repository.md|## 3. Value object|## 4. |$ORDER/domain/ShippingSnapshot.java|java|1"
    "$F05_DOC/02-domain-and-repository.md|## 4. Entity|### 4.1.|$ORDER/domain/Order.java|java|1"
    "$F05_DOC/02-domain-and-repository.md|## 5. Entity|### 5.1.|$ORDER/domain/OrderItem.java|java|1"
    "$F05_DOC/02-domain-and-repository.md|## 6. Entity|## 7. |$ORDER/domain/OrderStatusHistory.java|java|1"
    "$F05_DOC/02-domain-and-repository.md|## 7. Repository|### 7.1.|$ORDER/repository/OrderRepository.java|java|1"
    "$F05_DOC/02-domain-and-repository.md|### 7.1.|### 7.2.|$ORDER/repository/projection/OrderDetailRow.java|java|1"
    "$F05_DOC/02-domain-and-repository.md|## 8. Repository|## 9. |$ORDER/repository/StatusHistoryRepository.java|java|1"
    "$F05_DOC/02-domain-and-repository.md|## 9. Repository|## 10. |$ORDER/repository/OrderItemRepository.java|java|1"
    "$F05_DOC/02-domain-and-repository.md|### 10.1.|### 10.2.|$JAVA/common/config/BusinessProperties.java|java|1"
    "$F05_DOC/02-domain-and-repository.md|### 10.2.|### 10.3.|$JAVA/common/config/ClockConfig.java|java|1"
    "$F05_DOC/02-domain-and-repository.md|### 10.3.|### 10.4.|$ORDER/service/OrderNumberGenerator.java|java|1"

    # --- 03-service-and-error-codes.md ---
    "$F05_DOC/03-service-and-error-codes.md|### 3.1.|### 3.2.|$ORDER/dto/OrderItemRequest.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|### 3.2.|### 3.3.|$ORDER/dto/PlaceOrderRequest.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|### 3.3.|### 3.4.|$ORDER/dto/CancelOrderRequest.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|### 3.4.|### 3.5.|$ORDER/dto/TransitionStatusRequest.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|### 3.5.|### 3.6.|$ORDER/dto/OrderResponse.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|### 3.6.|### 3.7.|$ORDER/dto/OrderItemResponse.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|### 3.7.|### 3.8.|$ORDER/dto/StatusHistoryResponse.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|### 3.8.|## 4. |$ORDER/dto/OrderDetailResponse.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|### 4.1.|### 4.2.|$ORDER/repository/projection/ProductSnapshotRow.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|## 5. |## 6. |$ORDER/service/OrderService.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|## 6. |### 6.1.|$ORDER/service/OrderServiceImpl.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|## 7. |### 7.1.|$ORDER/service/OrderPlacement.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|## 8. |### 8.1.|$ORDER/service/OrderLifecycle.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|### 9.1.|### 9.2.|$JAVA/common/config/SchedulingConfig.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|### 9.2.|### 9.3.|$ORDER/service/OrderExpirationProperties.java|java|1"
    "$F05_DOC/03-service-and-error-codes.md|### 9.3.|### 9.4.|$ORDER/service/OrderExpirationJob.java|java|1"

    # --- 04-dto-mapper-controller.md ---
    "$F05_DOC/04-dto-mapper-controller.md|### 1.1.|## 2. |$JAVA/common/dto/PagedResponse.java|java|1"
    "$F05_DOC/04-dto-mapper-controller.md|### 2.1.|### 2.2.|$ORDER/mapper/OrderMapper.java|java|1"
    "$F05_DOC/04-dto-mapper-controller.md|### 3.1.|### 3.2.|$ORDER/controller/OrderController.java|java|1"
    "$F05_DOC/04-dto-mapper-controller.md|### 3.2.|### 3.3.|$ORDER/controller/CustomerOrderController.java|java|1"

    # --- 05-tests.md ---
    "$F05_DOC/05-tests.md|### 2.1.|### 2.2.|$TEST_JAVA/LockTestSupport.java|java|1"
    "$F05_DOC/05-tests.md|### 2.2.|### 2.3.|$TEST_ORDER/integration/OrderTestData.java|java|1"
    "$F05_DOC/05-tests.md|### 3.3.|### 3.4.|$TEST_ORDER/integration/OrderConcurrencyIntegrationTest.java|java|1"
    "$F05_DOC/05-tests.md|### 4.3.|### 4.4.|$TEST_ORDER/integration/OrderControllerIntegrationTest.java|java|1"

    # --- 06-verify-and-pr.md ---
    "$F05_DOC/06-verify-and-pr.md|Nội dung \`pr-body.md\`:|> Dùng \`--body-file\`|$F05_DOC/pr-body.md|markdown|1"

    # ===== F06 =====
    # --- 01-migration.md ---
    "$F06_DOC/01-migration.md|### 1.2.|### 1.3.|$MIG/V15__create_payments_and_refunds.sql|sql|1"

    # --- 02-domain-and-repository.md ---
    "$F06_DOC/02-domain-and-repository.md|## 2. Enum|## 3. |$PAYMENT/domain/PaymentStatus.java|java|1"
    "$F06_DOC/02-domain-and-repository.md|## 3. Enum|## 4. |$PAYMENT/domain/PaymentGateway.java|java|1"
    "$F06_DOC/02-domain-and-repository.md|## 4. Enum|## 5. |$PAYMENT/domain/RefundStatus.java|java|1"
    "$F06_DOC/02-domain-and-repository.md|## 5. Entity|### 5.1.|$PAYMENT/domain/Payment.java|java|1"
    "$F06_DOC/02-domain-and-repository.md|## 6. Entity|## 7. |$PAYMENT/domain/PaymentRefund.java|java|1"
    "$F06_DOC/02-domain-and-repository.md|## 7. Repository|### 7.1.|$PAYMENT/repository/PaymentRepository.java|java|1"
    "$F06_DOC/02-domain-and-repository.md|## 8. Repository|### 8.1.|$PAYMENT/repository/PaymentRefundRepository.java|java|1"

    # --- 03-service-and-error-codes.md ---
    "$F06_DOC/03-service-and-error-codes.md|### 1.1.|## 2. |$JAVA/common/error/UnprocessableContentException.java|java|1"
    "$F06_DOC/03-service-and-error-codes.md|### 3.1.|### 3.2.|$PAYMENT/dto/ResultKind.java|java|1"
    "$F06_DOC/03-service-and-error-codes.md|### 3.2.|### 3.3.|$PAYMENT/dto/PaymentIntentResult.java|java|1"
    "$F06_DOC/03-service-and-error-codes.md|### 3.3.|### 3.4.|$PAYMENT/dto/ConfirmPaymentResult.java|java|1"
    "$F06_DOC/03-service-and-error-codes.md|### 3.4.|### 3.5.|$PAYMENT/dto/RefundResult.java|java|1"
    "$F06_DOC/03-service-and-error-codes.md|### 3.5.|## 4. |$PAYMENT/dto/ConfirmRefundResult.java|java|1"
    "$F06_DOC/03-service-and-error-codes.md|## 4. |## 5. |$PAYMENT/service/PaymentService.java|java|1"
    "$F06_DOC/03-service-and-error-codes.md|## 5. |### 5.1.|$PAYMENT/service/PaymentServiceImpl.java|java|1"

    # --- 04-dto-mapper-controller.md ---
    "$F06_DOC/04-dto-mapper-controller.md|### 1.2.|### 1.3.|$PAYMENT/dto/CreatePaymentRequest.java|java|1"
    "$F06_DOC/04-dto-mapper-controller.md|### 1.3.|### 1.4.|$PAYMENT/dto/ConfirmPaymentRequest.java|java|1"
    "$F06_DOC/04-dto-mapper-controller.md|### 1.4.|### 1.5.|$PAYMENT/dto/FailPaymentRequest.java|java|1"
    "$F06_DOC/04-dto-mapper-controller.md|### 1.5.|### 1.6.|$PAYMENT/dto/RequestRefundRequest.java|java|1"
    "$F06_DOC/04-dto-mapper-controller.md|### 1.6.|### 1.7.|$PAYMENT/dto/ConfirmRefundRequest.java|java|1"
    "$F06_DOC/04-dto-mapper-controller.md|### 1.7.|### 1.8.|$PAYMENT/dto/PaymentResponse.java|java|1"
    "$F06_DOC/04-dto-mapper-controller.md|### 1.8.|### 1.9.|$PAYMENT/dto/RefundResponse.java|java|1"
    "$F06_DOC/04-dto-mapper-controller.md|### 2.1.|### 2.2.|$PAYMENT/mapper/PaymentMapper.java|java|1"
    "$F06_DOC/04-dto-mapper-controller.md|### 3.1.|### 3.2.|$PAYMENT/controller/OrderPaymentController.java|java|1"
    "$F06_DOC/04-dto-mapper-controller.md|### 3.2.|### 3.3.|$PAYMENT/controller/PaymentController.java|java|1"
    "$F06_DOC/04-dto-mapper-controller.md|### 3.3.|### 3.4.|$PAYMENT/controller/RefundController.java|java|1"
    "$F06_DOC/04-dto-mapper-controller.md|### 3.8.|### 3.9.|$JAVA/common/security/RequestActor.java|java|1"

    # --- 05-tests.md ---
    "$F06_DOC/05-tests.md|## 2. Hạ tầng test|## 3. |$TEST_PAYMENT/integration/PaymentTestData.java|java|1"
    "$F06_DOC/05-tests.md|### 3.3.|### 3.4.|$TEST_PAYMENT/integration/PaymentControllerIntegrationTest.java|java|1"
    "$F06_DOC/05-tests.md|### 4.2.|### 4.3.|$TEST_PAYMENT/integration/PaymentConcurrencyIntegrationTest.java|java|1"

    # --- 06-verify-and-pr.md ---
    "$F06_DOC/06-verify-and-pr.md|Nội dung \`pr-body.md\`:|> Dùng \`--body-file\`|$F06_DOC/pr-body.md|markdown|1"

    # ===== F07 =====
    # --- 01-migration.md ---
    "$F07_DOC/01-migration.md|### 1.2.|### 1.3.|$MIG/V16__create_dashboard_views.sql|sql|1"

    # --- 02-domain-and-repository.md ---
    "$F07_DOC/02-domain-and-repository.md|### 2.1.|### 2.2.|$DASHBOARD/dto/PeriodRevenueResponse.java|java|1"
    "$F07_DOC/02-domain-and-repository.md|### 2.2.|### 2.3.|$DASHBOARD/dto/CategoryRevenueResponse.java|java|1"
    "$F07_DOC/02-domain-and-repository.md|### 2.3.|### 2.4.|$DASHBOARD/dto/TopProductResponse.java|java|1"
    "$F07_DOC/02-domain-and-repository.md|### 2.4.|### 2.5.|$DASHBOARD/dto/TopCustomerResponse.java|java|1"
    "$F07_DOC/02-domain-and-repository.md|### 2.5.|### 2.6.|$DASHBOARD/dto/OrderFunnelResponse.java|java|1"
    "$F07_DOC/02-domain-and-repository.md|### 2.6.|### 2.7.|$DASHBOARD/dto/CohortRetentionResponse.java|java|1"
    "$F07_DOC/02-domain-and-repository.md|### 2.7.|### 2.8.|$DASHBOARD/dto/InventoryTurnoverResponse.java|java|1"
    "$F07_DOC/02-domain-and-repository.md|### 2.8.|### 2.9.|$DASHBOARD/dto/OrderSegmentResponse.java|java|1"
    "$F07_DOC/02-domain-and-repository.md|### 2.9.|### 2.10.|$DASHBOARD/dto/RevenueGrowthResponse.java|java|1"
    "$F07_DOC/02-domain-and-repository.md|### 2.10.|### 2.11.|$DASHBOARD/dto/TopCategoryResponse.java|java|1"
    "$F07_DOC/02-domain-and-repository.md|### 2.11.|### 2.12.|$DASHBOARD/dto/RevenueGroupingSetResponse.java|java|1"
    "$F07_DOC/02-domain-and-repository.md|### 2.12.|## 3. Repository|$DASHBOARD/dto/CustomerLifetimeValueResponse.java|java|1"
    "$F07_DOC/02-domain-and-repository.md|## 3. Repository|### 3.1.|$DASHBOARD/repository/DashboardQueryRepository.java|java|1"

    # --- 03-service-and-error-codes.md ---
    "$F07_DOC/03-service-and-error-codes.md|## 3. Interface|## 4. |$DASHBOARD/service/DashboardService.java|java|1"
    "$F07_DOC/03-service-and-error-codes.md|## 4. |### 4.1.|$DASHBOARD/service/DashboardServiceImpl.java|java|1"
    "$F07_DOC/03-service-and-error-codes.md|### 5.1.|### 5.2.|$DASHBOARD/service/RefreshOutcome.java|java|1"
    "$F07_DOC/03-service-and-error-codes.md|### 5.2.|### 5.3.|$DASHBOARD/service/DailyRevenueRefreshJob.java|java|1"
    "$F07_DOC/03-service-and-error-codes.md|## 6. |## 7. |$DASHBOARD/service/BusinessTimezoneCheck.java|java|1"

    # --- 04-dto-mapper-controller.md ---
    "$F07_DOC/04-dto-mapper-controller.md|### 2.2.|### 2.3.|$JAVA/common/error/ServiceUnavailableException.java|java|1"
    "$F07_DOC/04-dto-mapper-controller.md|### 4.1.|### 4.2.|$DASHBOARD/controller/DashboardController.java|java|1"
    "$F07_DOC/04-dto-mapper-controller.md|### 4.2.|### 4.3.|$DASHBOARD/controller/CustomerLifetimeValueController.java|java|1"

    # --- 05-tests.md ---
    "$F07_DOC/05-tests.md|### 2.1.|### 2.2.|$TEST_DASHBOARD/integration/DashboardTestData.java|java|1"
    "$F07_DOC/05-tests.md|### 3.2.|### 3.3.|$TEST_DASHBOARD/integration/DashboardControllerIntegrationTest.java|java|1"
    "$F07_DOC/05-tests.md|### 4.4.|### 4.5.|$TEST_DASHBOARD/integration/DashboardConcurrencyIntegrationTest.java|java|1"

    # --- 06-verify-and-pr.md ---
    "$F07_DOC/06-verify-and-pr.md|### 5.3.|### 5.4.|$JAVA/common/jdbc/RecordRowMapper.java|java|1"
    "$F07_DOC/06-verify-and-pr.md|### 5.3.|### 5.4.|$TEST_JAVA/common/jdbc/RecordRowMapperTest.java|java|2"
    "$F07_DOC/06-verify-and-pr.md|Nội dung \`pr-body.md\`:|> Dùng \`--body-file\`|$F07_DOC/pr-body.md|markdown|1"

    # ===== F08 =====
    # --- 01-migration.md ---
    "$F08_DOC/01-migration.md|### 3.2.|### 3.3.|$MIG/V17__create_search_normalize_function.sql|sql|1"
    "$F08_DOC/01-migration.md|### 4.2.|### 4.3.|$MIG/V18__create_search_and_cohort_indexes.sql|sql|1"
    "$F08_DOC/01-migration.md|### 4.2.|### 4.3.|$MIG/V18__create_search_and_cohort_indexes.sql.conf|properties|1"
    "$F08_DOC/01-migration.md|### 7.1.|### 7.2.|$TEST_JAVA/AbstractContainerBaseTest.java|java|1"
    "$F08_DOC/01-migration.md|### 7.1.|### 7.2.|src/test/resources/db/init/test-init.sql|sql|1"
    "$F08_DOC/01-migration.md|### 7.2.|### 7.3.|$TEST_JAVA/DatabaseSetupJdbcTest.java|java|1"
    "$F08_DOC/01-migration.md|### 7.3.|### 7.4.|$TEST_JAVA/feature/search/SearchSchemaJdbcTest.java|java|1"

    # --- 02-domain-and-repository.md ---
    "$F08_DOC/02-domain-and-repository.md|### 2.1.|### 2.2.|$JAVA/feature/product/domain/ProductStatus.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|### 2.2.|### 2.3.|$SEARCH/domain/SearchSort.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|### 2.3.|### 2.4.|$SEARCH/domain/MatchMode.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|### 2.4.|### 2.5.|$SEARCH/dto/ProductSearchCriteria.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|### 2.5.|### 2.6.|$SEARCH/dto/ProductSearchResponse.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|### 2.6.|### 2.7.|$ADMIN/domain/IndexUsage.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|### 2.7.|### 2.8.|$ADMIN/dto/IndexResponse.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|### 2.8.|### 2.9.|$ADMIN/dto/IndexUsageResponse.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|### 2.9.|### 2.10.|$ADMIN/dto/SlowQueryResponse.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|### 2.10.|## 3. |$ADMIN/dto/TableStatsResponse.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|### 3.1.|### 3.2.|$JAVA/common/jdbc/SqlStatement.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|## 4. Repository|### 4.1.|$SEARCH/repository/SearchQueryRepository.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|## 5. Repository|### 5.1.|$ADMIN/repository/AdminQueryRepository.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|### 6.1.|### 6.2.|$TEST_JAVA/common/jdbc/SqlStatementTest.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|### 6.2.|### 6.3.|$TEST_JAVA/feature/search/repository/SearchQueryRepositoryJdbcTest.java|java|1"
    "$F08_DOC/02-domain-and-repository.md|### 6.3.|### 6.4.|$TEST_JAVA/feature/admin/repository/AdminQueryRepositoryJdbcTest.java|java|1"

    # --- 03-service-and-error-codes.md ---
    "$F08_DOC/03-service-and-error-codes.md|### 3.1.|### 3.2.|$SEARCH/service/SearchService.java|java|1"
    "$F08_DOC/03-service-and-error-codes.md|### 3.2.|### 3.3.|$SEARCH/service/SearchServiceImpl.java|java|1"
    "$F08_DOC/03-service-and-error-codes.md|### 3.3.|### 3.4.|$SEARCH/dto/ProductSearchPageResponse.java|java|1"
    "$F08_DOC/03-service-and-error-codes.md|### 4.1.|### 4.2.|$ADMIN/service/AdminService.java|java|1"
    "$F08_DOC/03-service-and-error-codes.md|### 4.2.|### 4.3.|$ADMIN/service/AdminServiceImpl.java|java|1"
    "$F08_DOC/03-service-and-error-codes.md|### 5.1.|### 5.2.|$ADMIN/dto/ExplainResponse.java|java|1"
    "$F08_DOC/03-service-and-error-codes.md|### 5.1.|### 5.2.|$ADMIN/dto/PlanNodeResponse.java|java|2"
    "$F08_DOC/03-service-and-error-codes.md|### 5.2.|### 5.3.|$ADMIN/service/ExplainPlanReader.java|java|1"
    "$F08_DOC/03-service-and-error-codes.md|### 6.1.|### 6.2.|$ADMIN/dto/BenchmarkResponse.java|java|1"
    "$F08_DOC/03-service-and-error-codes.md|### 6.2.|### 6.3.|$ADMIN/service/LatencySampler.java|java|1"

    # --- 04-dto-mapper-controller.md ---
    "$F08_DOC/04-dto-mapper-controller.md|### 1.1.|### 1.2.|$SEARCH/dto/ProductSearchRequest.java|java|1"
    "$F08_DOC/04-dto-mapper-controller.md|### 2.1.|### 2.2.|$SEARCH/controller/SearchController.java|java|1"
    "$F08_DOC/04-dto-mapper-controller.md|### 2.2.|### 2.3.|$ADMIN/controller/AdminController.java|java|1"

    # --- 05-tests.md ---
    "$F08_DOC/05-tests.md|### 2.2.|### 2.3.|$TEST_JAVA/feature/search/integration/SearchControllerIntegrationTest.java|java|1"
    "$F08_DOC/05-tests.md|### 3.2.|### 3.3.|$TEST_JAVA/feature/admin/integration/AdminControllerIntegrationTest.java|java|1"
    "$F08_DOC/05-tests.md|### 4.2.|### 4.3.|$TEST_JAVA/IndexPlanJdbcTest.java|java|1"

    # --- 06-verify-and-pr.md ---
    "$F08_DOC/06-verify-and-pr.md|### 10.3.|### 10.4.|$MIG/V19__analyze_products_for_search_index.sql|sql|1"
    "$F08_DOC/06-verify-and-pr.md|### 14.2.|### 14.3.|$F08_DOC/pr-body.md|markdown|1"
)

FENCE='```'

fix=0
[ "${1:-}" = "--fix" ] && fix=1

# In số dòng của fence mở và fence đóng của block ```<lang> thứ <nth> giữa 2 heading.
# Output: "<dòng fence mở> <dòng fence đóng>", rỗng nếu không tìm thấy.
locate_block() {
    local file=$1 from=$2 to=$3 lang=$4 nth=$5
    awk -v from="$from" -v to="$to" -v open="$FENCE$lang" -v fence="$FENCE" -v want="$nth" '
        !inside && index($0, from) == 1 { inside = 1; next }
        inside && index($0, to)   == 1 { exit }
        inside && !collecting && $0 == open { seen++; if (seen == want) { collecting = 1; s = NR } next }
        inside &&  collecting && $0 == fence { print s, NR; exit }
    ' "$file"
}

# In nội dung block (không gồm 2 dòng fence).
extract_block() {
    local file=$1 pos
    pos=$(locate_block "$@")
    [ -z "$pos" ] && return 0
    set -- $pos
    sed -n "$(($1 + 1)),$(($2 - 1))p" "$file"
}

# Chép file nguồn đè vào block, giữ nguyên phần còn lại của doc.
fix_block() {
    local doc=$1 from=$2 to=$3 lang=$4 nth=$5 src=$6 pos tmp
    pos=$(locate_block "$doc" "$from" "$to" "$lang" "$nth")
    [ -z "$pos" ] && return 1
    set -- $pos
    tmp=$(mktemp)
    head -n "$1" "$doc" > "$tmp"
    cat "$src" >> "$tmp"
    tail -n "+$2" "$doc" >> "$tmp"
    mv "$tmp" "$doc"
}

rc=0
for pair in "${PAIRS[@]}"; do
    IFS='|' read -r doc from to src lang nth <<< "$pair"
    label="${src##*/}"

    if [ ! -f "$doc" ] || [ ! -f "$src" ]; then
        echo "SKIP  $label — thiếu file"
        continue
    fi

    tmp=$(mktemp)
    extract_block "$doc" "$from" "$to" "$lang" "$nth" > "$tmp"

    if [ ! -s "$tmp" ]; then
        echo "FAIL  $label — không tìm thấy block $lang #$nth trong $doc sau '$from'"
        rc=1
    elif diff -q "$tmp" "$src" > /dev/null; then
        echo "OK    $label"
    elif [ "$fix" = 1 ]; then
        fix_block "$doc" "$from" "$to" "$lang" "$nth" "$src"
        echo "FIXED $label — đã chép vào $doc '$from'"
    else
        echo "FAIL  $label LỆCH với $doc '$from'"
        echo "      (< doc | > file nguồn)"
        # diff trả 1 khi lệch — không có `|| true` thì set -e + pipefail dừng script ở cặp lệch đầu tiên
        diff "$tmp" "$src" | sed 's/^/      /' | head -20 || true
        rc=1
    fi

    rm -f "$tmp"
done

if [ "$rc" -ne 0 ]; then
    echo ""
    echo "Source of truth là file nguồn. Chạy lại với --fix để chép tự động:"
    echo "  ./scripts/check-doc-sync.sh --fix"
fi

exit "$rc"
