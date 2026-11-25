package com.tungph7.learnpostgres.feature.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.tungph7.learnpostgres.common.domain.BaseEntity;

@Entity
@Table(name = "addresses", schema = "app")
public class Address extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "customer_id", nullable = false)
  private Customer customer;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 16)
  private AddressType type;

  @Column(name = "full_name", nullable = false, length = 160)
  private String fullName;

  @Column(name = "phone", nullable = false, length = 32)
  private String phone;

  @Column(name = "line1", nullable = false, length = 255)
  private String line1;

  @Column(name = "line2", length = 255)
  private String line2;

  @Column(name = "ward", length = 80)
  private String ward;

  @Column(name = "district", length = 80)
  private String district;

  @Column(name = "city", nullable = false, length = 80)
  private String city;

  @Column(name = "state_region", length = 80)
  private String stateRegion;

  @Column(name = "postal_code", length = 20)
  private String postalCode;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "country_code", nullable = false, length = 2)
  private String countryCode = "VN";

  @Column(name = "is_default", nullable = false)
  private boolean isDefault = false;

  @Column(name = "is_deleted", nullable = false)
  private boolean deleted = false;

  protected Address() {
    // for JPA
  }

  public Address(
      Customer customer,
      AddressType type,
      String fullName,
      String phone,
      String line1,
      String city,
      String countryCode) {
    this.customer = customer;
    this.type = type;
    this.fullName = fullName;
    this.phone = phone;
    this.line1 = line1;
    this.city = city;
    this.countryCode = countryCode == null ? "VN" : countryCode.toUpperCase();
  }

  // -----------------------------------------------------------------
  // Getters / setters
  // -----------------------------------------------------------------

  public Customer getCustomer() {
    return customer;
  }

  public void setCustomer(Customer customer) {
    this.customer = customer;
  }

  public AddressType getType() {
    return type;
  }

  public void setType(AddressType type) {
    this.type = type;
  }

  public String getFullName() {
    return fullName;
  }

  public void setFullName(String fullName) {
    this.fullName = fullName;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getLine1() {
    return line1;
  }

  public void setLine1(String line1) {
    this.line1 = line1;
  }

  public String getLine2() {
    return line2;
  }

  public void setLine2(String line2) {
    this.line2 = line2;
  }

  public String getWard() {
    return ward;
  }

  public void setWard(String ward) {
    this.ward = ward;
  }

  public String getDistrict() {
    return district;
  }

  public void setDistrict(String district) {
    this.district = district;
  }

  public String getCity() {
    return city;
  }

  public void setCity(String city) {
    this.city = city;
  }

  public String getStateRegion() {
    return stateRegion;
  }

  public void setStateRegion(String stateRegion) {
    this.stateRegion = stateRegion;
  }

  public String getPostalCode() {
    return postalCode;
  }

  public void setPostalCode(String postalCode) {
    this.postalCode = postalCode;
  }

  public String getCountryCode() {
    return countryCode;
  }

  public void setCountryCode(String countryCode) {
    this.countryCode = countryCode == null ? "VN" : countryCode.toUpperCase();
  }

  public boolean isDefault() {
    return isDefault;
  }

  public void setDefault(boolean defaultAddress) {
    this.isDefault = defaultAddress;
  }

  public boolean isDeleted() {
    return deleted;
  }

  public void setDeleted(boolean deleted) {
    this.deleted = deleted;
  }
}
