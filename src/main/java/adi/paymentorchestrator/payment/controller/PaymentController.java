package adi.paymentorchestrator.payment.controller;

import adi.paymentorchestrator.exception.PaymentInProgressException;
import adi.paymentorchestrator.payment.dto.ClientReq;
import adi.paymentorchestrator.payment.dto.Response;
import adi.paymentorchestrator.payment.entity.Status;
import adi.paymentorchestrator.payment.service.PaymentOrchestrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentOrchestrationService service;

    @PostMapping("/payment")
    public ResponseEntity<Response> requestPayment(@RequestBody ClientReq req){
       Response response = service.idempotencyLogic(req);
       if(response.getStatus() == Status.INITIALIZED) 
           return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
       return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @ExceptionHandler(PaymentInProgressException.class)
    public ResponseEntity<String> handleInProgress(PaymentInProgressException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT) 
                .body("Payment attempt " + ex.getAttemptId() + " is still being processed. Please retry shortly.");
    }
}
