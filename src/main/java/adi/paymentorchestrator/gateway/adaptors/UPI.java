package adi.paymentorchestrator.gateway.adaptors;

import adi.paymentorchestrator.gateway.GatewayAdaptors;
import adi.paymentorchestrator.gateway.MockControl;
import adi.paymentorchestrator.gateway.dto.GatewayStatus;
import adi.paymentorchestrator.gateway.dto.gatewayReq;
import adi.paymentorchestrator.gateway.dto.gatewayRes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UPI implements GatewayAdaptors, MockControl {
    @Value("${gateway.upi.latency-ms}")
    private long latency;
    @Value("${gateway.upi.max-latency-ms}")
    private long maxLatency;
    @Value("${gateway.upi.timeout-rate}")
    private double timeOut;
    @Value("${gateway.upi.success-rate}")
    private double successRate;

    @Override
    public gatewayRes processPayment(gatewayReq req) {
        double roll = Math.random();
        GatewayStatus outcome;
        long latencyms;

        if(roll<timeOut){
            outcome = GatewayStatus.GATEWAY_TIMEOUT;
            latencyms = latency+500;
        }
        else if (roll < timeOut + successRate) {
            outcome = GatewayStatus.SUCCESS;
            latencyms = latency;
        } else {
            outcome = GatewayStatus.FAILED;
            latencyms = latency;
        }
        try{
            Thread.sleep(200);
        }catch (InterruptedException e){}

        return gatewayRes.builder()
                .gatewayName(getName())
                .latency(latencyms)
                .status(outcome)
                .gatewayId(outcome == GatewayStatus.GATEWAY_TIMEOUT ? null:"upi-"+ UUID.randomUUID().toString().substring(0,5))
                .build();
    }

    @Override
    public String getName() {
        return "UPI";
    }

    @Override
    public void setSuccessRate(double successRate) { this.successRate = successRate; }
    @Override
    public void setTimeoutRate(double timeoutRate) { this.timeOut = timeoutRate; }
    @Override
    public double getSuccessRate() { return successRate; }
    @Override
    public double getTimeoutRate() { return timeOut; }
}
