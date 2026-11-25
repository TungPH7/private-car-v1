package com.tungph7.learnpostgres.feature.payment.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.tungph7.learnpostgres.common.error.BadRequestException;
import com.tungph7.learnpostgres.common.error.ConflictException;
import com.tungph7.learnpostgres.common.error.ErrorCode;
import com.tungph7.learnpostgres.common.error.NotFoundException;
import com.tungph7.learnpostgres.common.error.UnprocessableContentException;
import com.tungph7.learnpostgres.feature.order.domain.Order;
import com.tungph7.learnpostgres.feature.order.domain.OrderStatus;
import com.tungph7.learnpostgres.feature.order.service.OrderService;
import com.tungph7.learnpostgres.feature.payment.domain.Payment;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentGateway;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentRefund;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentStatus;
import com.tungph7.learnpostgres.feature.payment.domain.RefundStatus;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmPaymentResult;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmRefundResult;
import com.tungph7.learnpostgres.feature.payment.dto.PaymentIntentResult;
import com.tungph7.learnpostgres.feature.payment.dto.RefundResult;
import com.tungph7.learnpostgres.feature.payment.repository.PaymentRefundRepository;
import com.tungph7.learnpostgres.feature.payment.repository.PaymentRepository;

/**
 * Cài đặt {@link PaymentService}.
 *
 * <p>Mọi đường ghi là MỘT câu SQL tự kiểm điều kiện của nó (bài 02) — service chỉ quyết định thứ tự
 * gọi và phân loại kết quả. Không câu nào dạng "SELECT kiểm trước rồi mới ghi": giữa hai câu luôn
 * có khe cho request khác chen vào.
 *
 * <p><b>READ COMMITTED, ghi tường minh</b> trên cả 5 method ghi (như {@code
 * OrderServiceImpl.placeOrder} của F05), vì chúng dựa vào đúng hành vi của nó:
 *
 * <ul>
 *   <li>UPDATE có điều kiện ({@code captureAtomic}, {@code markFailed}, {@code confirmRefund}) chờ
 *       khóa xong thì đánh giá lại WHERE trên row mới nhất → 0 row → đọc lại rồi phân loại.
 *       REPEATABLE READ ném 40001 thay vì 0 row: webhook gửi lặp nhận 409 thay vì 200.
 *   <li>{@code INSERT ... ON CONFLICT DO NOTHING} đụng row của request cùng key vừa commit: READ
 *       COMMITTED bỏ qua êm, câu SELECT kế tiếp thấy row đó → EXISTING. REPEATABLE READ ném 40001.
 *   <li>Trigger kiểm refund của V15 từ chối REPEATABLE READ (SQLSTATE 25000).
 * </ul>
 *
 * <p>Gọi từ bên trong một transaction đang mở thì method tham gia transaction đó và isolation ở đây
 * không có hiệu lực — caller phải tự ở READ COMMITTED.
 */
@Service
@Transactional(readOnly = true)
public class PaymentServiceImpl implements PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

  /** {@code idempotency_key VARCHAR(80)} ở cả 2 bảng (V15). */
  private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 80;

  /** {@code payment_reference} / {@code refund_reference VARCHAR(80)}. */
  private static final int REFERENCE_MAX_LENGTH = 80;

  /** {@code payment_refunds.reason VARCHAR(255)}. */
  private static final int REFUND_REASON_MAX_LENGTH = 255;

  /** {@code NUMERIC(12,2)}: phần lẻ thứ 3 trở đi bị DB làm tròn im lặng (QnA Q14). */
  private static final int MONEY_SCALE = 2;

  private final PaymentRepository paymentRepo;
  private final PaymentRefundRepository refundRepo;
  private final OrderService orderService;

  public PaymentServiceImpl(
      PaymentRepository paymentRepo,
      PaymentRefundRepository refundRepo,
      OrderService orderService) {
    this.paymentRepo = paymentRepo;
    this.refundRepo = refundRepo;
    this.orderService = orderService;
  }

  // ================================================================
  // Tạo payment — idempotent theo key
  // ================================================================

  /**
   * Bốn bước; thứ tự là phần quan trọng nhất:
   *
   * <ol>
   *   <li><b>Tra key TRƯỚC khi nhìn order.</b> Key đã có → đây là retry: cùng order + gateway → trả
   *       lại payment đó; khác → 422. Không kiểm lại order: retry tới sau khi chính payment này đã
   *       capture (order đã PAID) phải nhận payment cũ, không phải 409.
   *   <li>Lần đầu: order phải còn chờ thanh toán — PENDING và tổng lớn hơn 0 (V15 cấm payment 0
   *       đồng; coupon 100% cho ra order tổng 0). Đọc thường, KHÔNG khóa order: khóa order deadlock
   *       với webhook đang capture (bài 02 §7.2).
   *   <li>{@code insertIfAbsent} — {@code ON CONFLICT DO NOTHING} không chỉ định cột. Có row → NEW.
   *   <li>Rỗng → request khác vừa ghi trước (bước 1 chưa thấy vì lúc đó nó chưa commit). Tra lại
   *       theo key: có → phân loại như bước 1; không có → xung đột nằm ở {@code
   *       uq_payments_order_non_failed}: order đã có payment chưa FAILED → 409.
   * </ol>
   */
  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PaymentIntentResult createPaymentIntent(
      UUID orderId, PaymentGateway gateway, String idempotencyKey, String actor) {
    requireText(idempotencyKey, IDEMPOTENCY_KEY_MAX_LENGTH, "Idempotency-Key");
    if (gateway == null) {
      throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "gateway bắt buộc.");
    }

    Optional<Payment> byKey = paymentRepo.findByIdempotencyKey(idempotencyKey);
    if (byKey.isPresent()) {
      return replayIntent(byKey.get(), orderId, gateway);
    }

    Order order = orderService.getById(orderId);
    requirePayable(order);

    List<Payment> inserted =
        paymentRepo.insertIfAbsent(
            orderId, idempotencyKey, order.getTotal(), gateway.name(), actor);
    if (!inserted.isEmpty()) {
      Payment payment = inserted.getFirst();
      log.info(
          "Tạo payment {} cho order {}: {} {} qua {}",
          payment.getId(),
          order.getOrderNumber(),
          payment.getAmount(),
          payment.getCurrency(),
          gateway);
      return PaymentIntentResult.created(payment);
    }

    return paymentRepo
        .findByIdempotencyKey(idempotencyKey)
        .map(existing -> replayIntent(existing, orderId, gateway))
        .orElseThrow(
            () ->
                new ConflictException(
                    ErrorCode.PAY_ORDER_ALREADY_HAS_PAYMENT,
                    ("Order %s đã có một payment chưa FAILED — tiếp tục với payment đó, hoặc fail"
                            + " nó rồi tạo payment mới với key mới.")
                        .formatted(order.getOrderNumber())));
  }

  /**
   * Key đã có payment. Cùng order + gateway → retry của đúng request đó → EXISTING. Khác → key bị
   * dùng lại cho request khác → 422, và không trả payment của order khác cho người gọi.
   */
  private static PaymentIntentResult replayIntent(
      Payment existing, UUID orderId, PaymentGateway gateway) {
    if (!existing.getOrderId().equals(orderId) || existing.getGateway() != gateway) {
      throw keyReused(existing.getIdempotencyKey());
    }
    log.debug(
        "Payment {} đã có với key {} — trả lại", existing.getId(), existing.getIdempotencyKey());
    return PaymentIntentResult.existing(existing);
  }

  /** Order còn chờ thanh toán: còn chuyển được sang PAID (tức là PENDING), và có tiền để thu. */
  private static void requirePayable(Order order) {
    if (!order.getStatus().canTransitionTo(OrderStatus.PAID)) {
      throw new ConflictException(
          ErrorCode.PAY_ORDER_NOT_PAYABLE,
          "Order %s đang %s — chỉ order PENDING mới tạo được payment."
              .formatted(order.getOrderNumber(), order.getStatus()));
    }
    if (order.getTotal().signum() <= 0) {
      throw new ConflictException(
          ErrorCode.PAY_ORDER_NOT_PAYABLE,
          "Order %s có tổng tiền %s — không có gì để thanh toán."
              .formatted(order.getOrderNumber(), order.getTotal().toPlainString()));
    }
  }

  // ================================================================
  // Xác nhận thanh toán — webhook
  // ================================================================

  /**
   * UPDATE có điều kiện là trọng tài (QnA Q10): N webhook chạy chồng thì đúng một câu ra 1 row. 0
   * row KHÔNG có nghĩa là "đã capture" — đọc lại rồi mới phân loại ({@link #replayCapture}).
   *
   * <p>Capture và order → PAID nằm trong CÙNG transaction: cùng thành công hoặc cùng không.
   */
  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ConfirmPaymentResult confirmPayment(
      UUID paymentId, String paymentReference, String gatewayResponse) {
    requireText(paymentReference, REFERENCE_MAX_LENGTH, "paymentReference");

    if (paymentRepo.captureAtomic(paymentId, paymentReference, gatewayResponse) == 0) {
      return replayCapture(paymentId, paymentReference);
    }
    // captureAtomic đã clear persistence context → đọc ra trạng thái mới, không phải instance cũ.
    Payment payment = getById(paymentId);
    markOrderPaid(payment);
    log.info("Payment {} đã capture, mã giao dịch {}", paymentId, paymentReference);
    return ConfirmPaymentResult.captured(payment);
  }

  /**
   * {@code captureAtomic} ra 0 row: payment không tồn tại, hoặc không còn PENDING / AUTHORIZED.
   *
   * <p>Ack (webhook gửi lặp) khi payment ĐÃ THU TIỀN với ĐÚNG mã này. "Đã thu tiền" là cả nhóm
   * CAPTURED / PARTIALLY_REFUNDED / REFUNDED — webhook gửi lại sau khi đã hoàn một phần vẫn phải
   * được ack — nhận ra qua {@code captured_at}: V15 ép {@code captured_at} có ⇔ status thuộc nhóm
   * đó ({@code ck_payments_captured_at_with_status}).
   *
   * <p>Còn lại → 409: FAILED, hoặc đã thu với mã khác. Cả hai nghĩa là gateway có thể đã trừ tiền
   * mà payment này không ghi nhận được — việc của đối soát.
   */
  private ConfirmPaymentResult replayCapture(UUID paymentId, String reference) {
    Payment payment = getById(paymentId);
    if (payment.getCapturedAt() == null) {
      throw new ConflictException(
          ErrorCode.PAY_INVALID_STATE,
          "Payment %s đang %s — không capture được.".formatted(paymentId, payment.getStatus()));
    }
    if (!reference.equals(payment.getPaymentReference())) {
      throw new ConflictException(
          ErrorCode.PAY_INVALID_STATE,
          "Payment %s đã thu tiền với mã giao dịch %s — mã %s không khớp."
              .formatted(paymentId, payment.getPaymentReference(), reference));
    }
    log.debug("Payment {} đã capture với mã {} — webhook gửi lặp", paymentId, reference);
    return ConfirmPaymentResult.alreadyCaptured(payment);
  }

  /**
   * Order → PAID, chỉ khi order CÒN chuyển được sang PAID.
   *
   * <p><b>Kiểm trước, không {@code try/catch}.</b> {@code markPaid} là method
   * {@code @Transactional} của bean khác: exception đi qua proxy của nó đánh dấu CẢ transaction này
   * rollback-only. Bắt lại rồi chạy tiếp thì lúc commit Spring ném {@code
   * UnexpectedRollbackException} — capture mất theo, webhook nhận 500, gateway gửi lại, và lần nào
   * cũng thế vì order vẫn không PENDING.
   *
   * <p>Order đã rời PENDING (job expire, khách hủy trước khi webhook tới) thì giữ capture — gateway
   * đã trừ tiền thật — và log WARN: payment đã thu tiền mà order không PAID là thứ đối soát phải
   * quét ra để hoàn tiền (QnA Q13).
   *
   * <p>Khe còn lại: order đổi trạng thái giữa câu đọc ở đây và câu UPDATE trong {@code markPaid} →
   * {@code ORD_RACE_CONDITION}, cả transaction rollback, webhook nhận 409 và gateway gửi lại — lần
   * sau rơi vào nhánh WARN. Capture không mất, chỉ trễ tới lần gửi lại.
   */
  private void markOrderPaid(Payment payment) {
    Order order = orderService.getById(payment.getOrderId());
    if (order.getStatus().canTransitionTo(OrderStatus.PAID)) {
      orderService.markPaid(order.getId());
      return;
    }
    log.warn(
        "Payment {} đã thu {} {} nhưng order {} đang {} — không chuyển PAID. Cần đối soát: hoàn"
            + " tiền cho khách.",
        payment.getId(),
        payment.getAmount(),
        payment.getCurrency(),
        order.getOrderNumber(),
        order.getStatus());
  }

  // ================================================================
  // Đánh fail
  // ================================================================

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Payment markPaymentFailed(UUID paymentId, String reason) {
    if (paymentRepo.markFailed(paymentId, reason) == 0) {
      Payment payment = getById(paymentId);
      if (payment.getStatus() == PaymentStatus.FAILED) {
        return payment; // gọi lặp — giữ lý do của lần đầu
      }
      // Không còn PENDING / AUTHORIZED mà cũng không FAILED → đã thu tiền.
      throw new ConflictException(
          ErrorCode.PAY_INVALID_STATE,
          "Payment %s đang %s — đã thu tiền thì không fail được, hoàn tiền bằng refund."
              .formatted(paymentId, payment.getStatus()));
    }
    log.info("Payment {} chuyển FAILED: {}", paymentId, reason);
    return getById(paymentId);
  }

  // ================================================================
  // Đọc
  // ================================================================

  @Override
  public Payment getById(UUID id) {
    return paymentRepo.findById(id).orElseThrow(() -> paymentNotFound(id));
  }

  /** Order không tồn tại → 404 ({@code ORD_NOT_FOUND}), cùng quy ước đọc-con-theo-cha của F05. */
  @Override
  public List<Payment> listPaymentsForOrder(UUID orderId) {
    orderService.getById(orderId); // chỉ để ném 404 — OrderService không có existsById
    return paymentRepo.findByOrderIdOrderByCreatedAtDescIdDesc(orderId);
  }

  @Override
  public List<PaymentRefund> getRefundsForPayment(UUID paymentId) {
    if (!paymentRepo.existsById(paymentId)) {
      throw paymentNotFound(paymentId);
    }
    return refundRepo.findByPaymentIdOrderByRequestedAtDescIdDesc(paymentId);
  }

  @Override
  public PaymentRefund getRefund(UUID refundId) {
    return refundRepo
        .findById(refundId)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.PAY_REFUND_NOT_FOUND,
                    "Refund '%s' không tồn tại.".formatted(refundId)));
  }

  // ================================================================
  // Hoàn tiền — idempotent theo key
  // ================================================================

  /**
   * Bốn bước, cùng khuôn với {@link #createPaymentIntent}:
   *
   * <ol>
   *   <li><b>Tra key TRƯỚC.</b> Retry tới sau khi payment đã REFUNDED phải nhận refund cũ, không
   *       phải 409.
   *   <li>Lần đầu: {@code lockById} — khóa payment {@code FOR NO KEY UPDATE}, và là lần đọc payment
   *       ĐẦU TIÊN trong transaction (đọc trước thì nhận lại instance cũ — bài 02 §7.5). Dưới khóa:
   *       payment phải đã thu tiền, và riêng refund này không vượt số tiền đã thu.
   *   <li>{@code insertIfAbsent}. TỔNG refund đang giữ tiền do trigger AFTER của V15 kiểm — vượt →
   *       {@code DataIntegrityViolationException}, {@code GlobalExceptionHandler} dịch ra {@code
   *       PAY_REFUND_EXCEEDS_PAYMENT}. Service không tự cộng tổng: trigger là chốt đúng cả khi
   *       caller quên khóa.
   *   <li>Rỗng → request khác cùng key vừa ghi trước. Refund chỉ có UNIQUE của key đụng được lúc
   *       INSERT, nên tra lại chắc chắn thấy.
   * </ol>
   */
  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public RefundResult requestRefund(
      UUID paymentId, BigDecimal amount, String reason, String idempotencyKey, String actor) {
    requireText(idempotencyKey, IDEMPOTENCY_KEY_MAX_LENGTH, "Idempotency-Key");
    requireRefundAmount(amount);
    requireText(reason, REFUND_REASON_MAX_LENGTH, "reason");

    Optional<PaymentRefund> byKey = refundRepo.findByIdempotencyKey(idempotencyKey);
    if (byKey.isPresent()) {
      return replayRefund(byKey.get(), paymentId, amount, reason);
    }

    Payment payment = paymentRepo.lockById(paymentId).orElseThrow(() -> paymentNotFound(paymentId));
    requireRefundable(payment, amount);

    List<PaymentRefund> inserted =
        refundRepo.insertIfAbsent(paymentId, idempotencyKey, amount, reason, actor);
    if (!inserted.isEmpty()) {
      PaymentRefund refund = inserted.getFirst();
      log.info("Yêu cầu hoàn {} cho payment {} — refund {}", amount, paymentId, refund.getId());
      return RefundResult.created(refund);
    }

    PaymentRefund existing =
        refundRepo
            .findByIdempotencyKey(idempotencyKey)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "ON CONFLICT bỏ qua insert nhưng không tìm thấy refund với key '%s'"
                            .formatted(idempotencyKey)));
    return replayRefund(existing, paymentId, amount, reason);
  }

  /** Cùng payment + số tiền + lý do → retry → EXISTING. Khác → key bị dùng lại → 422. */
  private static RefundResult replayRefund(
      PaymentRefund existing, UUID paymentId, BigDecimal amount, String reason) {
    if (!existing.getPaymentId().equals(paymentId)
        || existing.getAmount().compareTo(amount) != 0
        || !existing.getReason().equals(reason)) {
      throw keyReused(existing.getIdempotencyKey());
    }
    log.debug(
        "Refund {} đã có với key {} — trả lại", existing.getId(), existing.getIdempotencyKey());
    return RefundResult.existing(existing);
  }

  /**
   * Chạy dưới khóa của {@code lockById}: status không đổi được cho tới hết transaction.
   *
   * <p>Riêng refund này vượt số tiền đã thu thì chặn ngay, không cần tới trigger: không có tổng nào
   * để cộng, và câu báo lỗi nói được con số cụ thể.
   */
  private static void requireRefundable(Payment payment, BigDecimal amount) {
    if (!payment.getStatus().isRefundable()) {
      throw new ConflictException(
          ErrorCode.PAY_INVALID_STATE,
          "Payment %s đang %s — chỉ refund được payment CAPTURED / PARTIALLY_REFUNDED."
              .formatted(payment.getId(), payment.getStatus()));
    }
    if (amount.compareTo(payment.getAmount()) > 0) {
      throw new ConflictException(
          ErrorCode.PAY_REFUND_EXCEEDS_PAYMENT,
          "Refund %s vượt số tiền đã thu %s của payment %s."
              .formatted(
                  amount.toPlainString(), payment.getAmount().toPlainString(), payment.getId()));
    }
  }

  // ================================================================
  // Xác nhận hoàn tiền — webhook
  // ================================================================

  /**
   * Refund PENDING → COMPLETED rồi tính lại status payment, cùng transaction.
   *
   * <p>{@code confirmRefund} đổi {@code status} nên trigger V15 chạy và khóa row payment — {@code
   * recomputeRefundStatus} ngay sau đó không phải chờ ai, và hai refund của cùng payment xác nhận
   * chồng nhau thì xếp hàng tại khóa đó: bên sau tính lại trên tổng đã gồm refund của bên trước.
   */
  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ConfirmRefundResult confirmRefund(
      UUID refundId, String refundReference, String gatewayResponse) {
    requireText(refundReference, REFERENCE_MAX_LENGTH, "refundReference");

    if (refundRepo.confirmRefund(refundId, refundReference, gatewayResponse) == 0) {
      return replayRefundConfirmation(refundId, refundReference);
    }
    PaymentRefund refund = getRefund(refundId);
    // Refund PENDING chỉ tồn tại khi payment CAPTURED / PARTIALLY_REFUNDED (trigger chặn từ lúc
    // tạo; REFUNDED thì không còn chỗ cho refund đang giữ tiền) → luôn 1 row. 0 là dữ liệu đã bị
    // sửa ngoài app — dừng hẳn còn hơn để refund COMPLETED mà status payment không đổi.
    if (paymentRepo.recomputeRefundStatus(refund.getPaymentId()) == 0) {
      throw new IllegalStateException(
          "Refund %s COMPLETED nhưng payment %s không ở CAPTURED / PARTIALLY_REFUNDED"
              .formatted(refundId, refund.getPaymentId()));
    }
    Payment payment = getById(refund.getPaymentId());
    log.info(
        "Refund {} COMPLETED, mã {} — payment {} giờ là {}",
        refundId,
        refundReference,
        payment.getId(),
        payment.getStatus());
    return ConfirmRefundResult.completed(refund, payment);
  }

  /**
   * {@code confirmRefund} ra 0 row: refund không tồn tại, hoặc không còn PENDING. Ack khi refund đã
   * COMPLETED với ĐÚNG mã này (webhook gửi lặp); còn lại → 409.
   */
  private ConfirmRefundResult replayRefundConfirmation(UUID refundId, String reference) {
    PaymentRefund refund = getRefund(refundId);
    if (refund.getStatus() != RefundStatus.COMPLETED) {
      throw new ConflictException(
          ErrorCode.PAY_INVALID_STATE,
          "Refund %s đang %s — không xác nhận được.".formatted(refundId, refund.getStatus()));
    }
    if (!reference.equals(refund.getRefundReference())) {
      throw new ConflictException(
          ErrorCode.PAY_INVALID_STATE,
          "Refund %s đã COMPLETED với mã %s — mã %s không khớp."
              .formatted(refundId, refund.getRefundReference(), reference));
    }
    log.debug("Refund {} đã COMPLETED với mã {} — webhook gửi lặp", refundId, reference);
    return ConfirmRefundResult.alreadyCompleted(refund, getById(refund.getPaymentId()));
  }

  // ================================================================
  // Helpers
  // ================================================================

  /**
   * Text từ request: bắt buộc, không toàn khoảng trắng, vừa cột. Kiểm ở đây để lỗi là 400 rõ field
   * — để DB chặn thì chuỗi quá dài là 22001 {@code DATA_INTEGRITY_VIOLATION} (409), không nói field
   * nào.
   */
  private static void requireText(String value, int maxLength, String field) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new BadRequestException(
          ErrorCode.VALIDATION_FAILED,
          "%s bắt buộc, không được rỗng, tối đa %d ký tự.".formatted(field, maxLength));
    }
  }

  /**
   * Lớn hơn 0, tối đa 2 chữ số lẻ. Phải kiểm TRƯỚC khi so với refund cũ cùng key: {@code
   * NUMERIC(12,2)} làm tròn im lặng — 100.005 lưu thành 100.01, và retry gửi lại đúng 100.005 sẽ
   * không khớp, bị coi là request khác.
   */
  private static void requireRefundAmount(BigDecimal amount) {
    if (amount == null
        || amount.signum() <= 0
        || amount.stripTrailingZeros().scale() > MONEY_SCALE) {
      throw new BadRequestException(
          ErrorCode.VALIDATION_FAILED,
          "amount phải lớn hơn 0 và có tối đa %d chữ số thập phân, nhận %s."
              .formatted(MONEY_SCALE, amount));
    }
  }

  /**
   * Thông báo không nhắc gì tới request đang giữ key: key toàn cục (UNIQUE cả bảng), người gọi
   * không được biết payment / refund của người khác.
   */
  private static UnprocessableContentException keyReused(String idempotencyKey) {
    return new UnprocessableContentException(
        ErrorCode.PAY_IDEMPOTENCY_KEY_REUSED,
        "Idempotency-Key '%s' đã được dùng cho một request khác — request mới cần key mới."
            .formatted(idempotencyKey));
  }

  private static NotFoundException paymentNotFound(UUID paymentId) {
    return new NotFoundException(
        ErrorCode.PAY_NOT_FOUND, "Payment '%s' không tồn tại.".formatted(paymentId));
  }
}
