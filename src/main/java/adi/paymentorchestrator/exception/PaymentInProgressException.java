package adi.paymentorchestrator.exception;

import lombok.Getter;

@Getter
public class PaymentInProgressException extends RuntimeException{
    private final Long attemptId;
    public PaymentInProgressException(Long attemptId) {
        super("Payment attempt " + attemptId + " is still processing");
        this.attemptId = attemptId;
    }
}
