package adi.paymentorchestrator.gateway;

/**
 * Test-only knob implemented by the mock gateway adaptors so the Part 7 harness
 * can force a gateway to always succeed / always time out at runtime, then restore it.
 * Real gateway integrations would not implement this.
 */
public interface MockControl {
    void setSuccessRate(double successRate);
    void setTimeoutRate(double timeoutRate);
    double getSuccessRate();
    double getTimeoutRate();
}
