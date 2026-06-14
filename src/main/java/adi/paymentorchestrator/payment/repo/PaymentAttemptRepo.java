package adi.paymentorchestrator.payment.repo;

import adi.paymentorchestrator.payment.entity.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentAttemptRepo extends JpaRepository<PaymentAttempt, String> {
    Optional<PaymentAttempt> findByIdempotencyKey(String idempotencyKey);
}
