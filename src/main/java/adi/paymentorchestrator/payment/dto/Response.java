package adi.paymentorchestrator.payment.dto;

import adi.paymentorchestrator.payment.entity.Status;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Response {
    private Status status;
    private Long id;
}
