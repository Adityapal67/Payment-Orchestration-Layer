package adi.paymentorchestrator.exception;

public class PaymentInProgressException extends RuntimeException{
    private final Long attemptId;
    public PaymentInProgressException(Long attemptId) {
        super("Payment attempt " + attemptId + " is still processing");
        this.attemptId = attemptId;
    }
    public Long getAttemptId() { return attemptId; }
}
