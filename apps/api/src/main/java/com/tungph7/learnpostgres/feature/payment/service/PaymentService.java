package com.tungph7.learnpostgres.feature.payment.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.tungph7.learnpostgres.feature.payment.domain.Payment;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentGateway;
import com.tungph7.learnpostgres.feature.payment.domain.PaymentRefund;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmPaymentResult;
import com.tungph7.learnpostgres.feature.payment.dto.ConfirmRefundResult;
import com.tungph7.learnpostgres.feature.payment.dto.PaymentIntentResult;
import com.tungph7.learnpostgres.feature.payment.dto.RefundResult;

/**
 * Thanh toán + hoàn tiền của order.
 *
 * <p>Mọi method ghi chịu được việc bị gọi lặp — client retry (theo idempotency key) hoặc gateway
 * gửi lại webhook — và lần lặp trả lại kết quả của lần đầu thay vì làm thêm lần nữa:
 *
 * <ul>
 *   <li><b>Tạo</b> ({@link #createPaymentIntent}, {@link #requestRefund}) — theo idempotency key.
 *       Cùng key + cùng request → {@code EXISTING}; cùng key + request khác → 422 {@code
 *       PAY_IDEMPOTENCY_KEY_REUSED}.
 *   <li><b>Webhook</b> ({@link #confirmPayment}, {@link #confirmRefund}) — theo mã giao dịch của
 *       gateway. Đã xác nhận với đúng mã đó → ack ({@code alreadyCaptured} / {@code
 *       alreadyCompleted}); mã khác → 409.
 *   <li><b>{@link #markPaymentFailed}</b> — đã FAILED thì gọi lại là no-op.
 * </ul>
 */
public interface PaymentService {

  // ---- Thanh toán ----

  /**
   * Tạo payment PENDING cho order, số tiền lấy từ {@code orders.total}. Chưa gọi gateway (F06 đơn
   * giản hoá — QnA Q16).
   *
   * @param actor ghi vào {@code created_by} / {@code updated_by}
   */
  PaymentIntentResult createPaymentIntent(
      UUID orderId, PaymentGateway gateway, String idempotencyKey, String actor);

  /**
   * Webhook của gateway: payment PENDING / AUTHORIZED → CAPTURED, rồi order PENDING → PAID. Order
   * không còn PENDING (hết hạn, bị hủy trước khi webhook tới) thì payment VẪN capture — gateway đã
   * trừ tiền thật — còn order giữ nguyên, log WARN để đối soát (QnA Q13).
   *
   * @param gatewayResponse payload gốc của gateway, JSON object dạng text; {@code null} nếu không
   *     có
   */
  ConfirmPaymentResult confirmPayment(
      UUID paymentId, String paymentReference, String gatewayResponse);

  /**
   * PENDING / AUTHORIZED → FAILED. Payment đã thu tiền thì không fail được — hoàn tiền bằng {@link
   * #requestRefund}.
   *
   * @return payment đọc lại sau khi chuyển
   */
  Payment markPaymentFailed(UUID paymentId, String reason);

  // ---- Đọc ----

  Payment getById(UUID id);

  /** Payment của order, mới nhất trước — kể cả FAILED. Order không tồn tại → 404. */
  List<Payment> listPaymentsForOrder(UUID orderId);

  // ---- Hoàn tiền ----

  /**
   * Yêu cầu hoàn {@code amount} (một phần hoặc toàn bộ) — refund PENDING, đang giữ tiền. Trigger
   * V15 chặn tổng refund PENDING + COMPLETED vượt số tiền đã thu.
   *
   * @param actor ghi vào {@code payment_refunds.actor}
   */
  RefundResult requestRefund(
      UUID paymentId, BigDecimal amount, String reason, String idempotencyKey, String actor);

  /**
   * Webhook hoàn tiền: refund PENDING → COMPLETED, rồi tính lại status của payment theo tổng refund
   * COMPLETED (PARTIALLY_REFUNDED / REFUNDED).
   */
  ConfirmRefundResult confirmRefund(UUID refundId, String refundReference, String gatewayResponse);

  /** Refund của payment, mới nhất trước. Payment không tồn tại → 404. */
  List<PaymentRefund> getRefundsForPayment(UUID paymentId);

  /**
   * Một refund theo id — để client theo dõi refund PENDING tới khi gateway xác nhận. Không tồn tại
   * → 404 {@code PAY_REFUND_NOT_FOUND}.
   */
  PaymentRefund getRefund(UUID refundId);
}
