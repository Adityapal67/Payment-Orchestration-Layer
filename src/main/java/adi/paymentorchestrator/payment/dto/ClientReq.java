package adi.paymentorchestrator.payment.dto;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientReq {
    private String idempotencyKey;
    private BigDecimal amount;
    private String currency;
}
