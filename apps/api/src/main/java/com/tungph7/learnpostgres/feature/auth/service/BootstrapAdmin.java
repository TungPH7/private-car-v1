package com.tungph7.learnpostgres.feature.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.tungph7.learnpostgres.feature.auth.config.AuthProperties;
import com.tungph7.learnpostgres.feature.auth.domain.UserAccount;
import com.tungph7.learnpostgres.feature.auth.repository.UserAccountRepository;

/**
 * Tạo tài khoản quản trị đầu tiên lúc khởi động, từ {@code APP_AUTH_BOOTSTRAP_ADMIN_EMAIL} và
 * {@code APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD}. Chỉ tạo khi email đó chưa có tài khoản: đổi biến môi
 * trường sau đó không đổi mật khẩu của tài khoản đã có. Không ghi email hay mật khẩu vào log.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

  static final String ROLE = "ADMIN";
  static final int MIN_PASSWORD_LENGTH = 12;

  private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

  private final AuthProperties props;
  private final UserAccountRepository users;
  private final PasswordEncoder passwordEncoder;

  public BootstrapAdmin(
      AuthProperties props, UserAccountRepository users, PasswordEncoder passwordEncoder) {
    this.props = props;
    this.users = users;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  public void run(ApplicationArguments args) {
    AuthProperties.BootstrapAdmin admin = props.bootstrapAdmin();
    if (!admin.configured()) {
      return;
    }
    if (admin.password().length() < MIN_PASSWORD_LENGTH) {
      throw new IllegalStateException(
          "APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD must have at least %d characters."
              .formatted(MIN_PASSWORD_LENGTH));
    }
    String email = admin.email().trim();
    if (users.findByEmail(email).isPresent()) {
      log.info("The bootstrap admin account exists already: left as it is.");
      return;
    }
    try {
      users.save(new UserAccount(email, passwordEncoder.encode(admin.password()), "Admin", ROLE));
      log.info("Created the bootstrap admin account.");
    } catch (DataIntegrityViolationException e) {
      // Một tiến trình khác vừa tạo nó (hai container cùng khởi động): không phải lỗi.
      log.info("The bootstrap admin account was created by another process.");
    }
  }
}
