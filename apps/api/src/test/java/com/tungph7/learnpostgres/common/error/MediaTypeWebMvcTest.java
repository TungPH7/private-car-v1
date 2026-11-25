package com.tungph7.learnpostgres.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tungph7.learnpostgres.common.logging.LoggingProperties;
import com.tungph7.learnpostgres.common.security.TestSecurityConfig;

/**
 * {@code GlobalExceptionHandler} với lỗi thương lượng kiểu nội dung — 415 ({@code Content-Type}) và
 * 406 ({@code Accept}) — trên exception THẬT do Spring MVC dựng.
 *
 * <p>Trước khi có hai handler này, cả hai rơi xuống catch-all: 500 {@code INTERNAL_ERROR} kèm log
 * ERROR cho lỗi của client. Hay gặp nhất là {@code curl -d '{...}'} không kèm {@code -H
 * 'Content-Type: application/json'} vào một endpoint nhận body JSON.
 *
 * <p>{@link ProbeController} lồng trong lớp test — cùng cách {@code MethodValidationWebMvcTest}: bị
 * loại khỏi component scan của test khác, đăng ký tường minh bằng {@code @Import}.
 */
@WebMvcTest(MediaTypeWebMvcTest.ProbeController.class)
@Import({
  TestSecurityConfig.class,
  GlobalExceptionHandler.class,
  ProblemDetailFactory.class,
  MediaTypeWebMvcTest.ProbeController.class
})
@EnableConfigurationProperties(LoggingProperties.class)
class MediaTypeWebMvcTest {

  /** Số lần method của probe đã chạy — 406 chỉ được phát hiện SAU khi method chạy xong. */
  static final AtomicInteger CALLS = new AtomicInteger();

  @Autowired MockMvc mockMvc;

  public record ProbeBody(String name) {}

  @RestController
  @RequestMapping("/media-probe")
  public static class ProbeController {

    @GetMapping
    public Map<String, String> read() {
      CALLS.incrementAndGet();
      return Map.of("name", "probe");
    }

    @PostMapping
    public Map<String, String> write(@RequestBody ProbeBody body) {
      CALLS.incrementAndGet();
      return Map.of("name", body.name());
    }
  }

  @BeforeEach
  void resetCalls() {
    CALLS.set(0);
  }

  @Test
  @DisplayName("body form-urlencoded (curl -d mặc định) → 415, Accept liệt kê kiểu đọc được")
  void formBodyIsUnsupported() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/media-probe")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .content("{\"name\":\"x\"}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(415))
            .andExpect(jsonPath("$.code").value(ErrorCode.UNSUPPORTED_MEDIA_TYPE.code()))
            .andReturn()
            .getResponse();

    assertThat(response.getHeader(HttpHeaders.ACCEPT)).contains(MediaType.APPLICATION_JSON_VALUE);
    // Spring ghi Content-Type kèm charset: 'application/x-www-form-urlencoded;charset=UTF-8'
    assertThat(response.getContentAsString(StandardCharsets.UTF_8))
        .contains("\"detail\":\"Content-Type 'application/x-www-form-urlencoded")
        .contains("' không đọc được. Gửi body dạng: application/json");
    assertThat(CALLS).hasValue(0);
  }

  @Test
  @DisplayName("body không có Content-Type → 415 (Spring coi là application/octet-stream)")
  void bodyWithoutContentTypeIsUnsupported() throws Exception {
    String json =
        mockMvc
            .perform(post("/media-probe").content("{\"name\":\"x\"}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value(ErrorCode.UNSUPPORTED_MEDIA_TYPE.code()))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(json)
        .contains("\"detail\":\"Content-Type 'application/octet-stream' không đọc được.");
    assertThat(CALLS).hasValue(0);
  }

  @Test
  @DisplayName("Accept: text/csv cho endpoint JSON → 406, body vẫn là problem+json")
  void unacceptableAcceptIsNotAcceptable() throws Exception {
    mockMvc
        .perform(get("/media-probe").accept(MediaType.parseMediaType("text/csv")))
        .andExpect(status().isNotAcceptable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(406))
        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_ACCEPTABLE.code()));

    // Spring chỉ biết không ghi được kết quả khi method đã chạy xong
    assertThat(CALLS).hasValue(1);
  }

  @Test
  @DisplayName("kiểu đúng → handler không can thiệp")
  void matchingMediaTypesPass() throws Exception {
    mockMvc
        .perform(
            post("/media-probe")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("x"));
  }
}
