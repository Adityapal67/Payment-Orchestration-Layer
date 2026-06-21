package adi.paymentorchestrator.circuit;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CircuitService {
    private final StringRedisTemplate redisTemplate;

    @Value("${circuit.success-threshold}")
    private double threshold;

    public void recordState(CircuitState state,String gatewayName){
        String key = "gateway:" + gatewayName + ":circuitState";
        redisTemplate.opsForValue().set(key,state.name());
    }

    public CircuitState getState(String gatewayName){
        String key = "gateway:" + gatewayName + ":circuitState";
        String s = redisTemplate.opsForValue().get(key);
        if(s==null){
            return CircuitState.CLOSED;
        }
        return CircuitState.valueOf(s);
    }

    public void evaluateState(String gatewayName, double successRate){
        if(successRate < threshold){
          recordState(CircuitState.OPEN,gatewayName);
        }else {
            recordState(CircuitState.CLOSED,gatewayName);
        }
    }
}
