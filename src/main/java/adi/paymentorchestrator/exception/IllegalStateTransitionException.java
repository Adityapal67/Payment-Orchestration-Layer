package adi.paymentorchestrator.exception;

import adi.paymentorchestrator.payment.entity.Status;
import lombok.Getter;

@Getter
public class IllegalStateTransitionException extends RuntimeException {
    private final Long attemptId;
    private final Status from;
    private final Status to;

    public IllegalStateTransitionException(Long attemptId, Status from, Status to) {
        super("Illegal state transition for attempt " + attemptId + ": " + from + " -> " + to);
        this.attemptId = attemptId;
        this.from = from;
        this.to = to;
    }
}
