package adi.paymentorchestrator.circuit;

import adi.paymentorchestrator.gateway.GatewayAdaptors;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Part 6: periodically nudges OPEN circuits into HALF_OPEN once their cooldown
 * has elapsed, so a probe request can test whether the gateway has recovered.
 */
@Component
@RequiredArgsConstructor
public class CircuitCooldownScheduler {
    private final CircuitService circuitService;
    private final List<GatewayAdaptors> gateways;

    @Scheduled(fixedDelay = 10_000)
    public void promoteOpenCircuits() {
        for (GatewayAdaptors gateway : gateways) {
            circuitService.tryHalfOpen(gateway.getName());
        }
    }
}
