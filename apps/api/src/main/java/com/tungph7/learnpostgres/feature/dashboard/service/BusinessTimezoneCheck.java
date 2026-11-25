package com.tungph7.learnpostgres.feature.dashboard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import com.tungph7.learnpostgres.common.config.BusinessProperties;
import com.tungph7.learnpostgres.feature.dashboard.repository.DashboardQueryRepository;

/**
 * Kiểm lúc khởi động: múi giờ cắt ngày của DB ({@code app.business_timezone()}, V16) phải đúng bằng
 * {@code app.business.timezone} của app — lệch thì app không start.
 *
 * <p>Hai nơi cùng quyết định "ngày / tháng của cửa hàng": báo cáo F07 cắt trong SQL theo hàm của
 * DB; phần còn lại của app theo config (tháng trong số đơn {@code ORD-YYYYMM-...}, tháng hiện tại
 * của cohort). Lệch nhau thì hai con số của cùng một tháng không khớp, và không có lỗi nào báo. DB
 * không đọc được config của app, nên app kiểm.
 *
 * <p>So đúng tên: {@code Asia/Saigon} là bí danh của {@code Asia/Ho_Chi_Minh} nhưng vẫn bị coi là
 * lệch — config phải ghi giống hệt migration, đọc một lần là biết hai bên cùng một múi giờ.
 *
 * <p>{@link InitializingBean}: chạy lúc tạo bean, trước khi web server mở cổng — cùng chỗ Spring
 * Boot chạy Flyway ({@code FlywayMigrationInitializer}). Bean đi qua {@code JdbcClient}, mà Spring
 * Boot xếp mọi bean {@code JdbcClient} sau bước migrate, nên hàm của V16 luôn có sẵn.
 */
@Component
class BusinessTimezoneCheck implements InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(BusinessTimezoneCheck.class);

  private final DashboardQueryRepository repository;
  private final BusinessProperties businessProperties;

  BusinessTimezoneCheck(
      DashboardQueryRepository repository, BusinessProperties businessProperties) {
    this.repository = repository;
    this.businessProperties = businessProperties;
  }

  @Override
  public void afterPropertiesSet() {
    String database = repository.businessTimezone();
    String application = businessProperties.timezone().getId();
    if (!application.equals(database)) {
      throw new IllegalStateException(
          ("app.business.timezone = %s nhưng app.business_timezone() của DB = %s. Báo cáo F07 cắt"
                  + " ngày theo DB, phần còn lại của app theo config — sửa config cho khớp, hoặc"
                  + " đổi hàm bằng một migration mới rồi REFRESH MATERIALIZED VIEW"
                  + " app.mv_daily_revenue.")
              .formatted(application, database));
    }
    log.info("Business timezone của app khớp DB: {}", application);
  }
}
