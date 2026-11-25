package com.tungph7.learnpostgres.feature.auth.domain;

/**
 * Một quyền ({@code app.permissions}, V20).
 *
 * @param code tên authority, như {@code product:delete}: claim {@code scope} của access token và
 *     {@code hasAuthority} của {@code SecurityConfig}
 * @param module nhóm quyền, như {@code product}: client ẩn cả một khu khi không có quyền nào của nó
 * @param httpMethod method của endpoint mà quyền mở
 * @param path mẫu path của endpoint đó, như {@code /api/products/{id}}
 */
public record Permission(String code, String module, String httpMethod, String path) {}
