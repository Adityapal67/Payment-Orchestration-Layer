package adi.paymentorchestrator.webhook;

import adi.paymentorchestrator.webhook.dto.WebhookPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
public class WebhookController {
    private final ReconciliationService reconciliationService;

    @PostMapping("/{gatewayName}")
    public ResponseEntity<WebhookResult> receive(@PathVariable String gatewayName,
                                                 @RequestBody WebhookPayload payload) {
        WebhookResult result = reconciliationService.handle(gatewayName, payload);
        return ResponseEntity.ok(result);
    }
}
