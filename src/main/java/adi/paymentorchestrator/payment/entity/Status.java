package adi.paymentorchestrator.payment.entity;



public enum Status {
    INITIALIZED,
    PROCESSING,
    SUCCESS,
    FAILED,
    GATEWAY_TIMEOUT,
    RETRY,
    REFUNDED
}
