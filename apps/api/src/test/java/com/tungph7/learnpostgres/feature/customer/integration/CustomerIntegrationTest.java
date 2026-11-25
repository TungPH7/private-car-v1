package com.tungph7.learnpostgres.feature.customer.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.tungph7.learnpostgres.AbstractContainerBaseTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
class CustomerIntegrationTest extends AbstractContainerBaseTest {

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  /**
   * Hikari đặt auto-commit=false → JdbcTemplate chạy ngoài transaction sẽ bị rollback lúc trả
   * connection về pool, DELETE thành no-op im lặng. Phải bọc trong TransactionTemplate.
   *
   * <p>Không cần xoá app.addresses: FK addresses_customer_id_fkey là ON DELETE CASCADE.
   */
  @BeforeEach
  @AfterEach
  void cleanup() {
    tx.executeWithoutResult(
        status -> jdbc.update("DELETE FROM app.customers WHERE email LIKE 'it-%'"));
  }

  // -----------------------------------------------------------------
  // Register
  // -----------------------------------------------------------------

  @Test
  @DisplayName("POST /customers → 201 + body")
  void registerCustomer() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "email": "it-john@x.com", "phone": "+84901234567",
                      "firstName": "John", "lastName": "Doe" }
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").exists())
        .andExpect(jsonPath("$.email").value("it-john@x.com"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.emailVerified").value(false));
  }

  @Test
  @DisplayName("POST /customers email trùng khác case → 409 CUS_EMAIL_TAKEN")
  void duplicateCaseInsensitiveEmail() throws Exception {
    register("it-dup@x.com", "A", "B");

    mockMvc
        .perform(
            post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "email": "IT-DUP@X.COM", "firstName": "C", "lastName": "D" }
                    """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CUS_EMAIL_TAKEN"));
  }

  @Test
  @DisplayName("POST /customers phone sai E.164 → 400 (Bean Validation chặn trước DB)")
  void invalidPhoneRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "email": "it-bad@x.com", "phone": "0901234567",
                      "firstName": "X", "lastName": "Y" }
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("POST /customers email sai format → 400")
  void invalidEmailRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "email": "not-an-email", "firstName": "X", "lastName": "Y" }
                    """))
        .andExpect(status().isBadRequest());
  }

  // -----------------------------------------------------------------
  // Lookup — citext qua HTTP
  // -----------------------------------------------------------------

  @Test
  @DisplayName("GET /by-email/{email} khác case → 200, trả đúng bản ghi (citext)")
  void getByEmailCaseInsensitive() throws Exception {
    register("it-case@x.com", "John", "Doe");

    mockMvc
        .perform(get("/api/v1/customers/by-email/{email}", "IT-CASE@X.COM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("it-case@x.com"));
  }

  @Test
  @DisplayName("GET /by-email/{email} không tồn tại → 404 CUS_NOT_FOUND")
  void getByEmailNotFound() throws Exception {
    mockMvc
        .perform(get("/api/v1/customers/by-email/{email}", "it-ghost@x.com"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CUS_NOT_FOUND"));
  }

  @Test
  @DisplayName("GET /{id}/profile → 200, customer chưa có đơn: 0 / null")
  void getProfile() throws Exception {
    String id = register("it-prof@x.com", "Pro", "File");

    mockMvc
        .perform(get("/api/v1/customers/{id}/profile", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("it-prof@x.com"))
        .andExpect(jsonPath("$.ordersCount").value(0))
        .andExpect(jsonPath("$.lastOrderAt").isEmpty());
  }

  // -----------------------------------------------------------------
  // Search
  // -----------------------------------------------------------------

  @Test
  @DisplayName("GET /customers?q= khớp họ tên, phân trang có totalElements")
  void searchByQuery() throws Exception {
    register("it-alice@x.com", "Alice", "Smith");
    register("it-bob@x.com", "Bob", "Smith");
    register("it-carol@x.com", "Carol", "Brown");

    mockMvc
        .perform(
            get("/api/v1/customers").param("q", "Smith").param("page", "0").param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.content.length()").value(2));
  }

  @Test
  @DisplayName("GET /customers không filter nào → 200 (native query không nổ type inference)")
  void searchWithoutFilters() throws Exception {
    register("it-nofilter@x.com", "No", "Filter");

    mockMvc
        .perform(get("/api/v1/customers").param("page", "0").param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray());
  }

  // -----------------------------------------------------------------
  // Address default
  // -----------------------------------------------------------------

  @Test
  @DisplayName("POST address đầu tiên với isDefault=true → response ĐÃ là true")
  void addFirstDefaultAddressReturnsTrue() throws Exception {
    String cId = register("it-first@x.com", "John", "Doe");

    mockMvc
        .perform(
            post("/api/v1/customers/{id}/addresses", cId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(addressBody("SHIPPING", "1 Main St", true)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.isDefault").value(true));
  }

  @Test
  @DisplayName("POST address isDefault=TRUE → swap cờ khỏi address cũ")
  void addAddressSwapsDefault() throws Exception {
    String cId = register("it-addr@x.com", "John", "Doe");
    String a1 = addAddress(cId, "SHIPPING", "1 St", true);
    String a2 = addAddress(cId, "SHIPPING", "2 St", true);

    Map<String, Boolean> flags = defaultFlags(cId, "SHIPPING");

    Assertions.assertThat(flags.get(a2)).isTrue();
    Assertions.assertThat(flags.get(a1)).isFalse();
  }

  @Test
  @DisplayName("POST address không xin default nhưng là cái đầu của type → tự set default (UX)")
  void firstAddressOfTypeAutoDefault() throws Exception {
    String cId = register("it-auto@x.com", "John", "Doe");
    String shipping = addAddress(cId, "SHIPPING", "1 St", false);
    String billing = addAddress(cId, "BILLING", "HQ", false);
    String shipping2 = addAddress(cId, "SHIPPING", "2 St", false);

    Assertions.assertThat(defaultFlags(cId, "SHIPPING"))
        .containsEntry(shipping, true)
        .containsEntry(shipping2, false);
    Assertions.assertThat(defaultFlags(cId, "BILLING")).containsEntry(billing, true);
  }

  @Test
  @DisplayName("POST /default set về address cũ hơn → 204 (regression partial unique)")
  void setDefaultBackToOlderAddress() throws Exception {
    String cId = register("it-back@x.com", "John", "Doe");
    String older = addAddress(cId, "SHIPPING", "1 St", true);
    String newer = addAddress(cId, "SHIPPING", "2 St", true);

    mockMvc
        .perform(post("/api/v1/customers/{c}/addresses/{a}/default", cId, older))
        .andExpect(status().isNoContent());

    Map<String, Boolean> flags = defaultFlags(cId, "SHIPPING");
    Assertions.assertThat(flags.get(older)).isTrue();
    Assertions.assertThat(flags.get(newer)).isFalse();
  }

  @Test
  @DisplayName("POST /default cho address của customer khác → 404 ADR_NOT_FOUND")
  void crossCustomerSetDefault() throws Exception {
    String c1 = register("it-cross1@x.com", "A", "B");
    String c2 = register("it-cross2@x.com", "C", "D");
    String addrOfC1 = addAddress(c1, "SHIPPING", "1 St", true);

    mockMvc
        .perform(post("/api/v1/customers/{c}/addresses/{a}/default", c2, addrOfC1))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ADR_NOT_FOUND"));
  }

  @Test
  @DisplayName("PATCH address của customer khác → 403 ADR_FORBIDDEN")
  void crossCustomerPatch() throws Exception {
    String c1 = register("it-patch1@x.com", "A", "B");
    String c2 = register("it-patch2@x.com", "C", "D");
    String addrOfC1 = addAddress(c1, "SHIPPING", "1 St", true);

    mockMvc
        .perform(
            patch("/api/v1/customers/{c}/addresses/{a}", c2, addrOfC1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "city": "HN" }
                    """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ADR_FORBIDDEN"));
  }

  @Test
  @DisplayName("PATCH address — field null giữ nguyên, field có giá trị thì ghi đè")
  void patchOnlyProvidedFields() throws Exception {
    String cId = register("it-patchok@x.com", "A", "B");
    String addrId = addAddress(cId, "SHIPPING", "1 St", true);

    mockMvc
        .perform(
            patch("/api/v1/customers/{c}/addresses/{a}", cId, addrId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "city": "HN" }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.city").value("HN"))
        .andExpect(jsonPath("$.line1").value("1 St"))
        .andExpect(jsonPath("$.fullName").value("X"));
  }

  @Test
  @DisplayName("DELETE address đang default → 204 + promote address còn lại")
  void softDeletePromotesNext() throws Exception {
    String cId = register("it-promote@x.com", "A", "B");
    String a1 = addAddress(cId, "SHIPPING", "1 St", true);
    String a2 = addAddress(cId, "SHIPPING", "2 St", false);

    mockMvc
        .perform(delete("/api/v1/customers/{c}/addresses/{a}", cId, a1))
        .andExpect(status().isNoContent());

    Map<String, Boolean> flags = defaultFlags(cId, "SHIPPING");
    Assertions.assertThat(flags).doesNotContainKey(a1).containsEntry(a2, true);
  }

  // -----------------------------------------------------------------
  // Concurrency — DB UNIQUE là chốt chặn cuối
  // -----------------------------------------------------------------

  @Test
  @DisplayName("10 request đồng thời cùng email → đúng 1 cái 201, còn lại 409")
  void concurrentSameEmail() throws Exception {
    String body =
        """
        { "email": "it-race@x.com", "firstName": "Race", "lastName": "Cond" }
        """;

    int threads = 10;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);

    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                start.await();
                return mockMvc
                    .perform(
                        post("/api/v1/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              }));
    }

    ready.await();
    start.countDown();

    int created = 0;
    int conflict = 0;
    for (Future<Integer> f : futures) {
      int statusCode = f.get();
      if (statusCode == 201) {
        created++;
      } else if (statusCode == 409) {
        conflict++;
      }
    }
    pool.shutdown();

    Assertions.assertThat(created).isEqualTo(1);
    Assertions.assertThat(conflict).isEqualTo(threads - 1);
  }

  // -----------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------

  private String register(String email, String first, String last) throws Exception {
    String body =
        """
        { "email": "%s", "firstName": "%s", "lastName": "%s" }
        """
            .formatted(email, first, last);
    String json =
        mockMvc
            .perform(
                post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).get("id").asString();
  }

  private String addAddress(String customerId, String type, String line1, boolean isDefault)
      throws Exception {
    String json =
        mockMvc
            .perform(
                post("/api/v1/customers/{id}/addresses", customerId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(addressBody(type, line1, isDefault)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json).get("id").asString();
  }

  private static String addressBody(String type, String line1, boolean isDefault) {
    return """
           { "type": "%s", "fullName": "X", "phone": "+84901111111",
             "line1": "%s", "city": "HCM", "countryCode": "VN", "isDefault": %s }
           """
        .formatted(type, line1, isDefault);
  }

  /** id → isDefault của các address (chưa xoá mềm) thuộc customer + type. */
  private Map<String, Boolean> defaultFlags(String customerId, String type) throws Exception {
    String json =
        mockMvc
            .perform(get("/api/v1/customers/{id}/addresses", customerId).param("type", type))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Map<String, Boolean> flags = new HashMap<>();
    for (JsonNode node : objectMapper.readTree(json)) {
      flags.put(node.get("id").asString(), node.get("isDefault").asBoolean());
    }
    return flags;
  }
}
