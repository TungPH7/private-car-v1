package com.tungph7.learnpostgres.feature.auth.repository;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.common.jdbc.RecordRowMapper;
import com.tungph7.learnpostgres.feature.auth.domain.Permission;

/** Quyền của một vai trò — dữ liệu tham chiếu của V20, đọc bằng SQL, không qua entity. */
@Repository
public class PermissionRepository {

  private final JdbcClient jdbc;

  public PermissionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Quyền của {@code roleCode}, theo thứ tự code; vai trò không có hoặc không quyền: rỗng. */
  public List<Permission> permissionsOf(String roleCode) {
    return jdbc.sql(
            """
            SELECT p.code, p.module, p.http_method, p.path
            FROM app.role_permissions rp
            JOIN app.permissions p ON p.code = rp.permission_code
            WHERE rp.role_code = :role
            ORDER BY p.code
            """)
        .param("role", roleCode)
        .query(RecordRowMapper.of(Permission.class))
        .list();
  }
}
