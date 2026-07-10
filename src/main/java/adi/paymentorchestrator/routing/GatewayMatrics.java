package adi.paymentorchestrator.routing;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayMatrics {
    private double successRate;
    private double latencyRate;
    private int sampleCount;
}
