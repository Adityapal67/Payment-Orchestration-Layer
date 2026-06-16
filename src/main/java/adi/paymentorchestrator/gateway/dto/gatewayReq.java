package adi.paymentorchestrator.gateway.dto;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class gatewayReq {
    private BigDecimal amount;
    private String currency;
    private String IdempotencyKey;
}
