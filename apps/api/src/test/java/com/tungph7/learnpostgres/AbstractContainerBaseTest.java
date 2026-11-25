package com.tungph7.learnpostgres;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Postgres container — share giữa mọi test class extends class này. Container start ở
 * static block, không stop (Testcontainers Ryuk cleanup khi JVM exit).
 *
 * <p>Không dùng @Container / @Testcontainers vì JUnit extension sẽ stop() container sau mỗi test
 * class → các class kế tiếp không connect được. Singleton pattern cần lifecycle do JVM static block
 * quản lý, Ryuk dọn dẹp khi JVM exit. @ServiceConnection (Spring Boot 3.1+) auto-bind container →
 * spring.datasource.* Không cần @DynamicPropertySource thủ công như pattern cũ.
 */
@ActiveProfiles("test")
public abstract class AbstractContainerBaseTest {

  /** PHẢI MATCH version PG trong docker-compose.yml (folder 02). */
  private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17-alpine");

  @ServiceConnection
  protected static final PostgreSQLContainer POSTGRES =
      (PostgreSQLContainer)
          new PostgreSQLContainer(POSTGRES_IMAGE)
              .withDatabaseName("learn_postgres_test")
              .withUsername("test")
              .withPassword("test")
              // Cùng DB dev ở hai điểm F08 dựa vào: locale (.env POSTGRES_INITDB_ARGS) và
              // pg_stat_statements (command của docker-compose.yml). fsync=off: mặc định của
              // PostgreSQLContainer, khai lại vì withCommand thay cả lệnh.
              .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF-8 --locale=C")
              .withCommand(
                  "postgres",
                  "-c",
                  "fsync=off",
                  "-c",
                  "shared_preload_libraries=pg_stat_statements")
              .withInitScript("db/init/test-init.sql") // tạo extensions + schema 'app' trước Flyway
              .withUrlParam(
                  "stringtype",
                  "unspecified") // bind String qua Oid.UNSPECIFIED → citext nhận text plain (folder
              // 03)
              .withReuse(true); // chỉ effect khi ~/.testcontainers.properties bật reuse

  static {
    POSTGRES.start();
  }
}
