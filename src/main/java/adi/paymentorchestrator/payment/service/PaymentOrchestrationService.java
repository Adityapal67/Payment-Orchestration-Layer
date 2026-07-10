package adi.paymentorchestrator.payment.service;

import adi.paymentorchestrator.exception.PaymentInProgressException;
import adi.paymentorchestrator.gateway.GatewayAdaptors;
import adi.paymentorchestrator.gateway.dto.GatewayStatus;
import adi.paymentorchestrator.gateway.dto.gatewayReq;
import adi.paymentorchestrator.gateway.dto.gatewayRes;
import adi.paymentorchestrator.payment.dto.ClientReq;
import adi.paymentorchestrator.payment.dto.Response;
import adi.paymentorchestrator.payment.entity.PaymentAttempt;
import adi.paymentorchestrator.payment.entity.Status;
import adi.paymentorchestrator.payment.repo.PaymentAttemptRepo;
import adi.paymentorchestrator.routing.RoutingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentOrchestrationService {
    private final PaymentAttemptRepo paymentAttemptRepo;
    private final RoutingService routingService;
    private final TransactionStateMachineService stateMachine;
    private final GatewayTimeoutInvestigationService timeoutInvestigationService;

    private static final Logger logger = LoggerFactory.getLogger(PaymentOrchestrationService.class);

    /**
     * Entry point from the controller. First enforces idempotency at the DB layer
     * (unique idempotencyKey); only a brand-new attempt proceeds to actually charge a gateway.
     */
    public Response idempotencyLogic(ClientReq req){
        PaymentAttempt paymentAttempt = new PaymentAttempt();
        try{
            paymentAttempt.setIdempotencyKey(req.getIdempotencyKey());
            paymentAttempt.setAmount(req.getAmount());
            paymentAttempt.setCurrency(req.getCurrency());
            paymentAttempt.setStatus(Status.INITIALIZED);
            paymentAttemptRepo.save(paymentAttempt);
        }catch(DataIntegrityViolationException ex){
            // A row with this idempotencyKey already exists -> return its current outcome.
            paymentAttempt = paymentAttemptRepo.findByIdempotencyKey(req.getIdempotencyKey()).orElseThrow(
                    () -> new IllegalStateException
                            ("Row should exist after UNIQUE violation but wasn't found for key: " +
                                    req.getIdempotencyKey())
            );

            Status status = paymentAttempt.getStatus();
            if(status == Status.SUCCESS){
                return response(paymentAttempt);
            } else if (status == Status.FAILED) {
                logger.warn("Payment attempt {} previously failed, idempotency key: {}", paymentAttempt.getId(), req.getIdempotencyKey());
                return response(paymentAttempt);
            }
            else{
                throw new PaymentInProgressException(paymentAttempt.getId());
            }
        }

        // Brand-new attempt: drive it through routing + the actual gateway call.
        return orchestrate(paymentAttempt);
    }

    /**
     * The core orchestration flow for a freshly-created attempt:
     * PROCESSING -> pick a gateway -> charge it -> record the outcome -> settle the status.
     */
    private Response orchestrate(PaymentAttempt attempt) {
        stateMachine.transition(attempt, Status.PROCESSING);

        GatewayAdaptors adapter = routingService.selectGateway();
        attempt.setSelectedGateway(adapter.getName());
        paymentAttemptRepo.save(attempt);

        gatewayReq req = buildGatewayReq(attempt);
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
            // GATEWAY_TIMEOUT: hand off to the investigation/failover flow.
            stateMachine.transition(attempt, Status.GATEWAY_TIMEOUT);
            return timeoutInvestigationService.investigate(attempt, req);
        }
    }

    private gatewayReq buildGatewayReq(PaymentAttempt attempt) {
        return gatewayReq.builder()
                .amount(attempt.getAmount())
                .currency(attempt.getCurrency())
                .IdempotencyKey(attempt.getIdempotencyKey())
                .build();
    }

    private Response response(PaymentAttempt attempt) {
        return Response.builder()
                .id(attempt.getId())
                .status(attempt.getStatus())
                .build();
    }
}
