package adi.paymentorchestrator.payment.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;


import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
public class PaymentAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false,unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private  String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String selectedGateway;
    private String gatewayReferenceId;

    @CreationTimestamp
    private LocalDateTime createdAt;
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
