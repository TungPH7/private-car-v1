package com.tungph7.learnpostgres.feature.customer.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.Type;

import com.tungph7.learnpostgres.common.domain.BaseEntity;
import com.tungph7.learnpostgres.common.domain.CitextType;

/**
 * Customer — khách hàng (B2C).
 *
 * <p>Email lưu kiểu citext (case-insensitive). status mapping VARCHAR + CHECK.
 */
@Entity
@Table(name = "customers", schema = "app")
public class Customer extends BaseEntity {

  @Type(CitextType.class)
  @Column(name = "email", nullable = false, columnDefinition = "public.citext")
  private String email;

  @Column(name = "phone", length = 32)
  private String phone;

  @Column(name = "first_name", nullable = false, length = 80)
  private String firstName;

  @Column(name = "last_name", nullable = false, length = 80)
  private String lastName;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private CustomerStatus status = CustomerStatus.ACTIVE;

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified = false;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  // -----------------------------------------------------------------
  // Constructors
  // -----------------------------------------------------------------

  protected Customer() {
    // for JPA
  }

  public Customer(String email, String phone, String firstName, String lastName) {
    this.email = email;
    this.phone = phone;
    this.firstName = firstName;
    this.lastName = lastName;
    this.status = CustomerStatus.ACTIVE;
    this.emailVerified = false;
  }

  // -----------------------------------------------------------------
  // Getters / setters
  // -----------------------------------------------------------------

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  public CustomerStatus getStatus() {
    return status;
  }

  public void setStatus(CustomerStatus status) {
    this.status = status;
  }

  public boolean isEmailVerified() {
    return emailVerified;
  }

  public void setEmailVerified(boolean emailVerified) {
    this.emailVerified = emailVerified;
  }

  public Instant getLastLoginAt() {
    return lastLoginAt;
  }

  public void setLastLoginAt(Instant lastLoginAt) {
    this.lastLoginAt = lastLoginAt;
  }
}
