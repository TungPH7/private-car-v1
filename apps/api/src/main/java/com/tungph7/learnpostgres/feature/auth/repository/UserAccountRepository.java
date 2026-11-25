package com.tungph7.learnpostgres.feature.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.tungph7.learnpostgres.feature.auth.domain.UserAccount;

@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

  /** Email là citext: {@code Admin@X.io} tìm ra tài khoản {@code admin@x.io}. */
  Optional<UserAccount> findByEmail(String email);
}
