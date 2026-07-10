package adi.paymentorchestrator.webhook.dto;

import adi.paymentorchestrator.gateway.dto.GatewayStatus;
import lombok.*;

/**
 * Async notification a gateway posts back to us once it finishes processing.
 * {@code idempotencyKey} is the receipt/metadata field we originally sent, and is
 * how we correlate the webhook back to a PaymentAttempt.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookPayload {
    private String webhookId;
    private String idempotencyKey;
    private String gatewayReferenceId;
    private GatewayStatus status;
}
