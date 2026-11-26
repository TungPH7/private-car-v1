package com.tungph7.learnpostgres.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;

/**
 * Probe của container: HEALTHCHECK trong {@code apps/api/Dockerfile} gọi {@code
 * /actuator/health/liveness} không token. Chỉ hai probe được mở thêm cạnh {@code /actuator/health};
 * mọi endpoint actuator khác vẫn bị chặn.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorProbesIntegrationTest extends AbstractContainerBaseTest {

  @Autowired MockMvc mockMvc;

  @Test
  void liveness_probe_is_open_without_a_token() throws Exception {
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void readiness_probe_is_open_without_a_token() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void flyway_endpoint_stays_blocked_without_a_token() throws Exception {
    mockMvc.perform(get("/actuator/flyway")).andExpect(status().isUnauthorized());
  }

  @Test
  void other_health_components_stay_blocked_without_a_token() throws Exception {
    mockMvc.perform(get("/actuator/health/db")).andExpect(status().isUnauthorized());
  }
}
