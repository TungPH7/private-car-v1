package com.tungph7.learnpostgres.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tungph7.learnpostgres.common.logging.LoggingProperties;
import com.tungph7.learnpostgres.common.security.TestSecurityConfig;

/**
 * {@code GlobalExceptionHandler.handleMethodValidation} chạy trên exception THẬT do Spring MVC
 * dựng.
 *
 * <p>Không tự {@code new} exception: điều cần chốt chính là cách Spring gói lỗi — ràng buộc trên
 * {@code @RequestParam}, và body {@code @Valid} của một method CŨNG có ràng buộc trực tiếp (Spring
 * đưa lỗi body vào {@code ParameterErrors} thay vì ném {@code MethodArgumentNotValidException}).
 * Cùng probe đó chốt thêm 2 việc F06 cần cho header {@code Idempotency-Key}: lỗi báo theo tên trên
 * request, và thiếu header bắt buộc thì detail nêu tên header.
 *
 * <p>{@link ProbeController} là controller riêng của test: lồng trong lớp test nên bị {@code
 * TestTypeExcludeFilter} loại khỏi component scan — không lọt vào context của test khác — và được
 * đăng ký tường minh bằng {@code @Import}.
 */
@WebMvcTest(MethodValidationWebMvcTest.ProbeController.class)
@Import({
  TestSecurityConfig.class,
  GlobalExceptionHandler.class,
  ProblemDetailFactory.class,
  MethodValidationWebMvcTest.ProbeController.class
})
@EnableConfigurationProperties(LoggingProperties.class)
class MethodValidationWebMvcTest {

  @Autowired MockMvc mockMvc;

  public record ProbeBody(@NotBlank String name) {}

  @RestController
  @RequestMapping("/probe")
  public static class ProbeController {

    @GetMapping
    public String param(@RequestParam @Min(1) int n) {
      return "ok";
    }

    @PostMapping
    public String bodyAndParam(@Valid @RequestBody ProbeBody body, @RequestParam @Min(1) int n) {
      return "ok";
    }

    /** Tên trên request khác tên biến Java — khai bằng {@code value}, không phải {@code name}. */
    @GetMapping("/header")
    public String header(@RequestHeader("X-Probe-Key") @Size(max = 3) String probeKey) {
      return "ok";
    }

    @GetMapping("/named-param")
    public String namedParam(@RequestParam("per_page") @Max(5) int perPage) {
      return "ok";
    }

    @GetMapping("/items/{item-code}")
    public String pathVariable(@PathVariable("item-code") @Size(max = 3) String code) {
      return "ok";
    }
  }

  @Test
  @DisplayName("@RequestParam @Min vi phạm → 400 VALIDATION_FAILED, field = tên tham số")
  void requestParamConstraint() throws Exception {
    mockMvc
        .perform(get("/probe").param("n", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
        .andExpect(jsonPath("$.detail").value("Validation failed for 1 field(s)."))
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].field").value("n"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").value(0))
        .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
  }

  @Test
  @DisplayName("hợp lệ → handler không can thiệp")
  void validRequestPasses() throws Exception {
    mockMvc.perform(get("/probe").param("n", "1")).andExpect(status().isOk());
  }

  /**
   * Method có ràng buộc trực tiếp nên Spring validate CẢ method: lỗi của body tới dưới dạng {@code
   * ParameterErrors} bên trong {@code HandlerMethodValidationException}. Handler phải báo lỗi body
   * theo tên field ({@code name}), không theo tên tham số Java ({@code body}).
   */
  @Test
  @DisplayName("body @Valid + @RequestParam cùng lỗi → cả hai vào errors[], body báo theo field")
  void bodyErrorsOfAMethodWithParameterConstraints() throws Exception {
    mockMvc
        .perform(
            post("/probe")
                .param("n", "0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
        .andExpect(jsonPath("$.errors.length()").value(2))
        .andExpect(jsonPath("$.errors[0].field").value("name"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").value(""))
        .andExpect(jsonPath("$.errors[1].field").value("n"));
  }

  /**
   * {@code field} là tên client gửi ({@code X-Probe-Key}, {@code per_page}, {@code item-code}),
   * không phải tên biến Java ({@code probeKey}, {@code perPage}, {@code code}) — client không có
   * cách nào biết tên biến. Ba annotation đều khai tên bằng {@code value}: test chốt luôn rằng
   * Spring đã điền alias {@code name} cho handler đọc.
   */
  @ParameterizedTest(name = "{0} → field {2}")
  @MethodSource("namedRequestValues")
  @DisplayName("ràng buộc trên header / param / path variable → field = tên trên request")
  void fieldIsTheNameOnTheRequest(String uri, String headerValue, String field, Object rejected)
      throws Exception {
    MockHttpServletRequestBuilder request = get(uri);
    if (headerValue != null) {
      request.header("X-Probe-Key", headerValue);
    }
    mockMvc
        .perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].field").value(field))
        .andExpect(jsonPath("$.errors[0].rejectedValue").value(rejected));
  }

  static Stream<Arguments> namedRequestValues() {
    return Stream.of(
        Arguments.of("/probe/header", "abcd", "X-Probe-Key", "abcd"),
        Arguments.of("/probe/named-param?per_page=6", null, "per_page", 6),
        Arguments.of("/probe/items/abcd", null, "item-code", "abcd"));
  }

  /**
   * Thiếu header bắt buộc: lỗi ở bước đọc tham số ({@code MissingRequestHeaderException}), chưa tới
   * validation. Detail nêu tên header, không chép message của Spring ("for method parameter type
   * String" — chi tiết cài đặt).
   */
  @Test
  @DisplayName("thiếu header bắt buộc → 400 INVALID_PARAMETER, nêu tên header")
  void missingRequiredHeader() throws Exception {
    mockMvc
        .perform(get("/probe/header"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
        .andExpect(jsonPath("$.header").value("X-Probe-Key"))
        .andExpect(jsonPath("$.detail").value("Thiếu header bắt buộc 'X-Probe-Key'."));
  }
}
