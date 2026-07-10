package adi.paymentorchestrator.test;

import adi.paymentorchestrator.circuit.CircuitService;
import adi.paymentorchestrator.circuit.CircuitState;
import adi.paymentorchestrator.gateway.GatewayAdaptors;
import adi.paymentorchestrator.gateway.MockControl;
import adi.paymentorchestrator.gateway.dto.GatewayStatus;
import adi.paymentorchestrator.gateway.dto.gatewayReq;
import adi.paymentorchestrator.payment.dto.ClientReq;
import adi.paymentorchestrator.payment.dto.Response;
import adi.paymentorchestrator.payment.entity.PaymentAttempt;
import adi.paymentorchestrator.payment.entity.Status;
import adi.paymentorchestrator.payment.repo.PaymentAttemptRepo;
import adi.paymentorchestrator.payment.service.GatewayTimeoutInvestigationService;
import adi.paymentorchestrator.payment.service.PaymentOrchestrationService;
import adi.paymentorchestrator.routing.RoutingService;
import adi.paymentorchestrator.webhook.ReconciliationService;
import adi.paymentorchestrator.webhook.WebhookResult;
import adi.paymentorchestrator.webhook.dto.WebhookPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

/**
 * Part 7: on-demand harness that exercises each behavior end-to-end.
 * These endpoints are for manual verification only, not production traffic.
 */
@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class TestScenarioController {

    private final PaymentOrchestrationService orchestrationService;
    private final GatewayTimeoutInvestigationService investigationService;
    private final RoutingService routingService;
    private final CircuitService circuitService;
    private final ReconciliationService reconciliationService;
    private final PaymentAttemptRepo repo;
    private final StringRedisTemplate redis;
    private final List<GatewayAdaptors> gateways;

    // ------------------------------------------------------------------
    // Scenario 1 — Normal routing distribution
    // ------------------------------------------------------------------
    @PostMapping("/scenario1")
    public Map<String, Object> scenario1(@RequestParam(defaultValue = "100") int count) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String key = "s1-" + UUID.randomUUID();
                futures.add(pool.submit(() ->
                        orchestrationService.idempotencyLogic(newReq(key)).getId()));
            }
            Map<String, Integer> distribution = new TreeMap<>();
            for (Future<Long> f : futures) {
                repo.findById(f.get()).ifPresent(a ->
                        distribution.merge(nullSafe(a.getSelectedGateway()), 1, Integer::sum));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", count);
            out.put("distribution", distribution);
            out.put("currentScores", scoresMap());
            return out;
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Scenario 2 — Gateway failure trips the circuit and drops traffic
    // ------------------------------------------------------------------
    @PostMapping("/scenario2")
    public Map<String, Object> scenario2(@RequestParam(defaultValue = "Stripe") String gateway) {
        resetGateway(gateway);
        // Same path a real burst of failures would take.
        for (int i = 0; i < 20; i++) {
            routingService.recordOutcome(gateway, GatewayStatus.FAILED, 200);
        }
        CircuitState state = circuitService.getState(gateway);

        Map<String, Integer> picks = new TreeMap<>();
        for (int i = 0; i < 50; i++) {
            picks.merge(routingService.selectGateway().getName(), 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gateway", gateway);
        out.put("circuitState", state);
        out.put("selectionCountsOver50", picks);
        out.put("gatewayExcludedFromRouting", !picks.containsKey(gateway));
        out.put("note", "failures fed via recordOutcome — the same path real gateway failures take");
        return out;
    }

    // ------------------------------------------------------------------
    // Scenario 3 — Timeout investigation + failover to another gateway
    // ------------------------------------------------------------------
    @PostMapping("/scenario3")
    public Map<String, Object> scenario3(@RequestParam(defaultValue = "RazorPay") String gateway,
                                         @RequestParam(defaultValue = "20") int count) {
        MockControl mock = mockFor(gateway);
        double origSuccess = mock.getSuccessRate();
        double origTimeout = mock.getTimeoutRate();
        resetGateway(gateway);

        int resolvedByFailover = 0, recoveredOnSameGateway = 0, stuckInTimeout = 0, failed = 0;
        try {
            mock.setTimeoutRate(1.0);   // this gateway always times out
            mock.setSuccessRate(0.0);
            for (int i = 0; i < count; i++) {
                // Fixture: an attempt that just timed out on `gateway` (the state orchestration
                // would hand to investigate()). Set directly — this is test setup, not the flow.
                PaymentAttempt a = new PaymentAttempt();
                a.setIdempotencyKey("s3-" + UUID.randomUUID());
                a.setAmount(new BigDecimal("100"));
                a.setCurrency("INR");
                a.setStatus(Status.GATEWAY_TIMEOUT);
                a.setSelectedGateway(gateway);
                a = repo.save(a);

                gatewayReq req = gatewayReq.builder()
                        .amount(a.getAmount()).currency(a.getCurrency())
                        .IdempotencyKey(a.getIdempotencyKey()).build();

                Response res = investigationService.investigate(a, req);
                PaymentAttempt settled = repo.findById(res.getId()).orElseThrow();

                if (settled.getStatus() == Status.GATEWAY_TIMEOUT) {
                    stuckInTimeout++;
                } else if (settled.getStatus() == Status.FAILED) {
                    failed++;
                } else if (gateway.equals(settled.getSelectedGateway())) {
                    recoveredOnSameGateway++;   // the "actually succeeded despite timeout" path
                } else {
                    resolvedByFailover++;        // settled on a different gateway
                }
            }
        } finally {
            mock.setSuccessRate(origSuccess);
            mock.setTimeoutRate(origTimeout);
            resetGateway(gateway);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timedOutGateway", gateway);
        out.put("count", count);
        out.put("resolvedByFailover", resolvedByFailover);
        out.put("recoveredOnSameGateway", recoveredOnSameGateway);
        out.put("failedNoGatewayLeft", failed);
        out.put("stuckInTimeout", stuckInTimeout);
        out.put("investigationAlwaysResolved", stuckInTimeout == 0);
        return out;
    }

    // ------------------------------------------------------------------
    // Scenario 4 — Concurrent same-key requests create exactly one row
    // ------------------------------------------------------------------
    @PostMapping("/scenario4")
    public Map<String, Object> scenario4() throws Exception {
        String key = "s4-" + UUID.randomUUID();
        Callable<String> task = () -> {
            try {
                Response r = orchestrationService.idempotencyLogic(newReq(key));
                return "ok id=" + r.getId() + " status=" + r.getStatus();
            } catch (Exception e) {
                return "rejected: " + e.getClass().getSimpleName();
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<String> f1 = pool.submit(task);
        Future<String> f2 = pool.submit(task);
        String r1 = f1.get(), r2 = f2.get();
        pool.shutdown();

        long rows = repo.countByIdempotencyKey(key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idempotencyKey", key);
        out.put("thread1", r1);
        out.put("thread2", r2);
        out.put("rowCount", rows);
        out.put("exactlyOneRow", rows == 1);
        return out;
    }

    // ------------------------------------------------------------------
    // Scenario 5 — Late webhook from a second gateway is caught as double-charge
    // ------------------------------------------------------------------
    @PostMapping("/scenario5")
    public Map<String, Object> scenario5() {
        String key = "s5-" + UUID.randomUUID();
        // Attempt already settled SUCCESS on gateway A.
        PaymentAttempt a = new PaymentAttempt();
        a.setIdempotencyKey(key);
        a.setAmount(new BigDecimal("100"));
        a.setCurrency("INR");
        a.setStatus(Status.SUCCESS);
        a.setSelectedGateway("RazorPay");
        a.setGatewayReferenceId("rzp-original");
        repo.save(a);

        // Late webhook arrives from gateway B for the same payment.
        WebhookPayload payload = WebhookPayload.builder()
                .webhookId("wh-" + UUID.randomUUID())
                .idempotencyKey(key)
                .gatewayReferenceId("str-late")
                .status(GatewayStatus.SUCCESS)
                .build();

        WebhookResult first = reconciliationService.handle("Stripe", payload);
        WebhookResult redelivery = reconciliationService.handle("Stripe", payload);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idempotencyKey", key);
        out.put("webhookFromOtherGateway", first);
        out.put("redeliverySamePayload", redelivery);
        out.put("doubleChargeDetected", first == WebhookResult.DOUBLE_CHARGE_DETECTED);
        out.put("dedupWorks", redelivery == WebhookResult.DUPLICATE);
        return out;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------
    private ClientReq newReq(String key) {
        return ClientReq.builder()
                .idempotencyKey(key)
                .amount(new BigDecimal("100"))
                .currency("INR")
                .build();
    }

    private MockControl mockFor(String name) {
        return (MockControl) gateways.stream()
                .filter(g -> g.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown gateway: " + name));
    }

    private void resetGateway(String name) {
        redis.delete("gateway:" + name + ":outcome");
        circuitService.recordState(CircuitState.CLOSED, name);
    }

    private Map<String, Double> scoresMap() {
        Map<String, Double> m = new TreeMap<>();
        routingService.getGatewayScores().forEach(s -> m.put(s.getGatewayName(), s.getScore()));
        return m;
    }

    private String nullSafe(String gateway) {
        return gateway == null ? "NONE" : gateway;
    }
}
