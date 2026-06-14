package adi.paymentorchestrator.payment.service;

import adi.paymentorchestrator.exception.PaymentInProgressException;
import adi.paymentorchestrator.payment.dto.ClientReq;
import adi.paymentorchestrator.payment.dto.Response;
import adi.paymentorchestrator.payment.entity.PaymentAttempt;
import adi.paymentorchestrator.payment.entity.Status;
import adi.paymentorchestrator.payment.repo.PaymentAttemptRepo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentOrchestrationService {
    private final PaymentAttemptRepo paymentAttemptRepo;
    private static final Logger logger = LoggerFactory.getLogger(PaymentOrchestrationService.class);

    public Response idempotencyLogic(ClientReq req){
        PaymentAttempt paymentAttempt = new PaymentAttempt();
        Response res = new Response();
        try{
            paymentAttempt.setIdempotencyKey(req.getIdempotencyKey());
            paymentAttempt.setAmount(req.getAmount());
            paymentAttempt.setCurrency(req.getCurrency());
            paymentAttempt.setStatus(Status.INITIALIZED);
           paymentAttemptRepo.save(paymentAttempt);

           res = Response.builder().id(paymentAttempt.getId())
                  .status(paymentAttempt.getStatus())
                  .build();
        }catch(DataIntegrityViolationException ex){
         paymentAttempt =  paymentAttemptRepo.findByIdempotencyKey(req.getIdempotencyKey()).orElseThrow(
                 () -> new IllegalStateException
                         ("Row should exist after UNIQUE violation but wasn't found for key: " +
                                 req.getIdempotencyKey())
         );

         Status status = paymentAttempt.getStatus();
         if(status == Status.SUCCESS){
             return Response.builder().id(paymentAttempt.getId())
                     .status(paymentAttempt.getStatus())
                     .build();
         } else if (status == Status.FAILED) {
             logger.warn("Payment attempt {} previously failed, idempotency key: {}", paymentAttempt.getId(), req.getIdempotencyKey());
             return Response.builder().id(paymentAttempt.getId())
                     .status(paymentAttempt.getStatus())
                     .build();
         }
         else{
             throw new PaymentInProgressException(paymentAttempt.getId());
         }
        }
        return res;
    }
}
