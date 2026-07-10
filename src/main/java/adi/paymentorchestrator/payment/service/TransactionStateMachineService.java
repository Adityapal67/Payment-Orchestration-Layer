package adi.paymentorchestrator.payment.service;

import adi.paymentorchestrator.exception.IllegalStateTransitionException;
import adi.paymentorchestrator.payment.entity.PaymentAttempt;
import adi.paymentorchestrator.payment.entity.Status;
import adi.paymentorchestrator.payment.repo.PaymentAttemptRepo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * Central guard for every status change on a PaymentAttempt.
 * Nothing else in the codebase should call attempt.setStatus(...) directly —
 * routing this through one place keeps the lifecycle legal and auditable.
 */
@Service
@RequiredArgsConstructor
public class TransactionStateMachineService {
    private static final Logger logger = LoggerFactory.getLogger(TransactionStateMachineService.class);

    private final PaymentAttemptRepo paymentAttemptRepo;

    /**
     * The legal edges of the payment lifecycle. A transition is allowed only if
     * newStatus is in the set mapped from the current status.
     */
    private static final Map<Status, Set<Status>> ALLOWED = Map.of(
            Status.INITIALIZED, Set.of(Status.PROCESSING),
            Status.PROCESSING, Set.of(Status.SUCCESS, Status.FAILED, Status.GATEWAY_TIMEOUT),
            Status.GATEWAY_TIMEOUT, Set.of(Status.SUCCESS, Status.RETRY),
            Status.RETRY, Set.of(Status.PROCESSING),
            Status.SUCCESS, Set.of(Status.REFUNDED),
            Status.FAILED, Set.of(),
            Status.REFUNDED, Set.of()
    );

    /**
     * Validate and apply a status change. Persists on success.
     *
     * @throws IllegalStateTransitionException if the edge (current -> newStatus) is not legal.
     */
    public PaymentAttempt transition(PaymentAttempt attempt, Status newStatus) {
        Status current = attempt.getStatus();
        Set<Status> legalTargets = ALLOWED.getOrDefault(current, Set.of());

        if (!legalTargets.contains(newStatus)) {
            throw new IllegalStateTransitionException(attempt.getId(), current, newStatus);
        }

        attempt.setStatus(newStatus);
        PaymentAttempt saved = paymentAttemptRepo.save(attempt);
        logger.info("Attempt {} transitioned {} -> {}", saved.getId(), current, newStatus);
        return saved;
    }
}
