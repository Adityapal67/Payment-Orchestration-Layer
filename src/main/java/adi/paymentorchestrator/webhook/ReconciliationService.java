package adi.paymentorchestrator.webhook;

import adi.paymentorchestrator.payment.entity.PaymentAttempt;
import adi.paymentorchestrator.payment.entity.Status;
import adi.paymentorchestrator.payment.repo.PaymentAttemptRepo;
import adi.paymentorchestrator.payment.service.TransactionStateMachineService;
import adi.paymentorchestrator.webhook.dto.WebhookPayload;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Part 5: reconciles asynchronous gateway webhooks against our own record of a payment.
 * Handles three concerns: idempotent delivery (dedup), double-charge detection, and
 * settling attempts that were left ambiguous by a timeout/retry.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {
    private static final Logger logger = LoggerFactory.getLogger(ReconciliationService.class);
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final PaymentAttemptRepo paymentAttemptRepo;
    private final TransactionStateMachineService stateMachine;
    private final StringRedisTemplate redisTemplate;

    public WebhookResult handle(String gatewayName, WebhookPayload payload) {
        // 1) Deduplicate: SETNX on the webhook id. If the key already existed, we've seen it.
        String dedupKey = "webhook:" + gatewayName + ":" + payload.getWebhookId();
        Boolean firstTime = redisTemplate.opsForValue().setIfAbsent(dedupKey, "processed", DEDUP_TTL);
        if (!Boolean.TRUE.equals(firstTime)) {
            logger.info("Duplicate webhook {} from {} ignored", payload.getWebhookId(), gatewayName);
            return WebhookResult.DUPLICATE;
        }

        // 2) Correlate back to our attempt via the idempotency key we originally sent.
        Optional<PaymentAttempt> found = paymentAttemptRepo.findByIdempotencyKey(payload.getIdempotencyKey());
        if (found.isEmpty()) {
            logger.warn("Webhook {} references unknown idempotencyKey {}", payload.getWebhookId(), payload.getIdempotencyKey());
            return WebhookResult.NOT_FOUND;
        }
        PaymentAttempt attempt = found.get();
        Status current = attempt.getStatus();

        // 3) Double-charge: already succeeded, but this webhook is from a *different* gateway.
        if (current == Status.SUCCESS) {
            if (!gatewayName.equals(attempt.getSelectedGateway())) {
                logger.error("DOUBLE CHARGE for attempt {}: settled on {} but webhook from {} — refund required",
                        attempt.getId(), attempt.getSelectedGateway(), gatewayName);
                // TODO: trigger real refund API call here.
                return WebhookResult.DOUBLE_CHARGE_DETECTED;
            }
            return WebhookResult.IGNORED;
        }

        // 4) The webhook resolves an ambiguous attempt (we never got a definitive answer).
        if (current == Status.GATEWAY_TIMEOUT) {
            attempt.setGatewayReferenceId(payload.getGatewayReferenceId());
            stateMachine.transition(attempt, Status.SUCCESS);
            logger.info("Webhook settled attempt {} -> SUCCESS", attempt.getId());
            return WebhookResult.RESOLVED_SUCCESS;
        }
        if (current == Status.RETRY) {
            // RETRY can't jump straight to SUCCESS in the state machine; go via PROCESSING.
            attempt.setGatewayReferenceId(payload.getGatewayReferenceId());
            stateMachine.transition(attempt, Status.PROCESSING);
            stateMachine.transition(attempt, Status.SUCCESS);
            logger.info("Webhook settled retrying attempt {} -> SUCCESS", attempt.getId());
            return WebhookResult.RESOLVED_SUCCESS;
        }

        return WebhookResult.IGNORED;
    }
}
