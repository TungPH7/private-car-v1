package com.tungph7.learnpostgres.feature.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

class AccessTokenResolverTest {

  private final AccessTokenResolver resolver = new AccessTokenResolver();

  @Test
  void reads_the_bearer_token_of_authorization() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer abc.def.ghi");

    assertThat(resolver.resolve(request)).isEqualTo("abc.def.ghi");
  }

  @Test
  void prefers_authorization_bearer_to_the_access_token_header() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer from-authorization");
    request.addHeader(AccessTokenResolver.HEADER, "from-header");

    assertThat(resolver.resolve(request)).isEqualTo("from-authorization");
  }

  @Test
  void reads_the_access_token_header_when_authorization_carries_basic_auth() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
    request.addHeader(AccessTokenResolver.HEADER, "abc.def.ghi");

    assertThat(resolver.resolve(request)).isEqualTo("abc.def.ghi");
  }

  @Test
  void reads_the_access_token_header_alone() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(AccessTokenResolver.HEADER, "abc.def.ghi");

    assertThat(resolver.resolve(request)).isEqualTo("abc.def.ghi");
  }

  @Test
  void no_token_is_null() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

    assertThat(resolver.resolve(request)).isNull();
  }

  @Test
  void a_malformed_access_token_header_is_an_invalid_token() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(AccessTokenResolver.HEADER, "not a token");

    assertThatThrownBy(() -> resolver.resolve(request))
        .isInstanceOf(OAuth2AuthenticationException.class);
  }
}
