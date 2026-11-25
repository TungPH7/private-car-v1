package com.tungph7.learnpostgres.feature.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Cách lấy cặp token, đặt tên như {@code grant_type} của OAuth 2.0 (RFC 6749 § 4.3, § 6). */
public enum GrantType {
  /** Email và mật khẩu: đăng nhập. */
  @JsonProperty("password")
  PASSWORD,

  /** Một refresh token còn dùng được: làm mới. */
  @JsonProperty("refresh_token")
  REFRESH_TOKEN
}
