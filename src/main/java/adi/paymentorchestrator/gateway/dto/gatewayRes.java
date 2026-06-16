package adi.paymentorchestrator.gateway.dto;

import lombok.*;
import org.hibernate.query.sql.internal.ParameterRecognizerImpl;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class gatewayRes {
private  String gatewayId;
private String gatewayName;
private GatewayStatus status;
private Long latency;
}
