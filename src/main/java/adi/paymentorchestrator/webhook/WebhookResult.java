package adi.paymentorchestrator.webhook;

public enum WebhookResult {
    /** This webhookId was already processed; ignored for idempotency. */
    DUPLICATE,
    /** No PaymentAttempt matched the idempotencyKey in the payload. */
    NOT_FOUND,
    /** Attempt was already SUCCESS on another gateway — a second gateway also charged. */
    DOUBLE_CHARGE_DETECTED,
    /** An ambiguous (timeout/retry) attempt was settled to SUCCESS by this webhook. */
    RESOLVED_SUCCESS,
    /** Webhook was valid but required no state change. */
    IGNORED
}
