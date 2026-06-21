package adi.paymentorchestrator.gateway;

import adi.paymentorchestrator.gateway.dto.gatewayReq;
import adi.paymentorchestrator.gateway.dto.gatewayRes;

public interface GatewayAdaptors {
     gatewayRes processPayment(gatewayReq req);

     String getName();

}
