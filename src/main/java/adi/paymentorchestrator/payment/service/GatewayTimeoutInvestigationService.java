package adi.paymentorchestrator.payment.service;

import adi.paymentorchestrator.gateway.GatewayAdaptors;
import adi.paymentorchestrator.gateway.dto.GatewayStatus;
import adi.paymentorchestrator.gateway.dto.gatewayReq;
import adi.paymentorchestrator.gateway.dto.gatewayRes;
import adi.paymentorchestrator.payment.dto.Response;
import adi.paymentorchestrator.payment.entity.PaymentAttempt;
import adi.paymentorchestrator.payment.entity.Status;
import adi.paymentorchestrator.routing.RoutingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Part 4: resolves the ambiguity of a GATEWAY_TIMEOUT.
 * A timeout means "we don't know if the money moved" — so we first ask the gateway
 * (simulated here) whether the charge actually went through. If it did, we settle
 * as SUCCESS; if not, we fail over to other gateways until one settles or we run out.
 *
 * Precondition: the attempt is already in {@link Status#GATEWAY_TIMEOUT} when investigate() is called.
 */
@Service
@RequiredArgsConstructor
public class GatewayTimeoutInvestigationService {
    private static final Logger logger = LoggerFactory.getLogger(GatewayTimeoutInvestigationService.class);

    private final RoutingService routingService;
    private final TransactionStateMachineService stateMachine;

    @Value("${timeout.investigation.success-probability}")
    private double successProbability;

    public Response investigate(PaymentAttempt attempt, gatewayReq req) {
        String timedOutGateway = attempt.getSelectedGateway();

        // Step 1: simulate the "status check" call back to the gateway that timed out.
        boolean actuallySucceeded = Math.random() < successProbability;
        if (actuallySucceeded) {
            logger.info("Investigation: attempt {} actually SUCCEEDED on {} despite timeout",
                    attempt.getId(), timedOutGateway);
            attempt.setGatewayReferenceId("recovered-" + UUID.randomUUID().toString().substring(0, 8));
            stateMachine.transition(attempt, Status.SUCCESS);
            return response(attempt);
        }

        // Step 2: genuinely unreachable -> mark RETRY and fail over to other gateways.
        logger.warn("Investigation: attempt {} genuinely timed out on {}, failing over",
                attempt.getId(), timedOutGateway);
        stateMachine.transition(attempt, Status.RETRY);

        Set<String> excluded = new HashSet<>();
        excluded.add(timedOutGateway);

        while (true) {
            GatewayAdaptors adapter;
            try {
                adapter = routingService.selectGateway(excluded);
            } catch (IllegalStateException noneLeft) {
                // No healthy gateway remains — settle as FAILED (RETRY -> PROCESSING -> FAILED).
                logger.error("Failover exhausted for attempt {}, marking FAILED", attempt.getId());
                stateMachine.transition(attempt, Status.PROCESSING);
                stateMachine.transition(attempt, Status.FAILED);
                return response(attempt);
            }

            attempt.setSelectedGateway(adapter.getName());
            stateMachine.transition(attempt, Status.PROCESSING);
            logger.info("Failover: retrying attempt {} on {}", attempt.getId(), adapter.getName());

            gatewayRes res = adapter.processPayment(req);
            routingService.recordOutcome(adapter.getName(), res.getStatus(), res.getLatency());

            if (res.getStatus() == GatewayStatus.SUCCESS) {
                attempt.setGatewayReferenceId(res.getGatewayId());
                stateMachine.transition(attempt, Status.SUCCESS);
                return response(attempt);
            } else if (res.getStatus() == GatewayStatus.FAILED) {
                stateMachine.transition(attempt, Status.FAILED);
                return response(attempt);
            } else {
                // Timed out again — exclude this gateway and keep trying.
                excluded.add(adapter.getName());
                stateMachine.transition(attempt, Status.GATEWAY_TIMEOUT);
                stateMachine.transition(attempt, Status.RETRY);
            }
        }
    }

    private Response response(PaymentAttempt attempt) {
        return Response.builder()
                .id(attempt.getId())
                .status(attempt.getStatus())
                .build();
    }
}
