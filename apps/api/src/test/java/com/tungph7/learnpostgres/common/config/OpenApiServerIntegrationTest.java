package com.tungph7.learnpostgres.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The servers of {@code /v3/api-docs}, which "Try it out" of Swagger UI calls: the origin the
 * document was read from, so Swagger UI calls the API it came from: on this machine, on the port of
 * a lane, and behind Caddy on the host of an environment. A fixed URL there, such as
 * http://localhost:8080, sends every call of Swagger UI on a host to the machine of the reader.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiServerIntegrationTest extends AbstractContainerBaseTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;

  private List<String> serverUrls(String scheme, String host, int port) throws Exception {
    String json =
        mockMvc
            .perform(
                get("/v3/api-docs")
                    .with(
                        request -> {
                          request.setScheme(scheme);
                          request.setServerName(host);
                          request.setServerPort(port);
                          request.setSecure("https".equals(scheme));
                          return request;
                        }))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    JsonNode servers = objectMapper.readTree(json).path("servers");
    List<String> urls = new ArrayList<>();
    servers.forEach(server -> urls.add(server.path("url").asString()));
    return urls;
  }

  @Test
  @DisplayName("the server is the origin of the request on an environment host")
  void serverIsTheHostOfTheEnvironment() throws Exception {
    assertThat(serverUrls("https", "private-car-v1-api-dev.ao.t2g.io.vn", 443))
        .containsExactly("https://private-car-v1-api-dev.ao.t2g.io.vn");
  }

  @Test
  @DisplayName("the server is the origin of the request on the port of a lane")
  void serverIsThePortOfTheLane() throws Exception {
    assertThat(serverUrls("http", "localhost", 3101)).containsExactly("http://localhost:3101");
  }
}
