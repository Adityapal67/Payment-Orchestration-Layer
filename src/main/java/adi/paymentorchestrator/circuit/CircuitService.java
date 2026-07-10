package adi.paymentorchestrator.circuit;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Per-gateway circuit breaker backed by Redis.
 * State keys:  gateway:{name}:circuitState  -> OPEN | CLOSED | HALF_OPEN
 *              gateway:{name}:openedAt       -> epoch millis the breaker last opened (Part 6 cooldown)
 */
@Service
@RequiredArgsConstructor
public class CircuitService {
    private static final Logger logger = LoggerFactory.getLogger(CircuitService.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${circuit.success-threshold}")
    private double threshold;

    @Value("${circuit.cooldown-ms}")
    private long cooldownMs;

    @Value("${circuit.halfopen.probe-probability}")
    private double probeProbability;

    private String stateKey(String gatewayName) {
        return "gateway:" + gatewayName + ":circuitState";
    }

    private String openedAtKey(String gatewayName) {
        return "gateway:" + gatewayName + ":openedAt";
    }

    public void recordState(CircuitState state, String gatewayName) {
        redisTemplate.opsForValue().set(stateKey(gatewayName), state.name());
        // Stamp/clear the cooldown marker so the scheduler knows how long we've been OPEN.
        if (state == CircuitState.OPEN) {
            redisTemplate.opsForValue().set(openedAtKey(gatewayName), String.valueOf(System.currentTimeMillis()));
        } else if (state == CircuitState.CLOSED) {
            redisTemplate.delete(openedAtKey(gatewayName));
        }
    }

    public CircuitState getState(String gatewayName) {
        String s = redisTemplate.opsForValue().get(stateKey(gatewayName));
        if (s == null) {
            return CircuitState.CLOSED;
        }
        return CircuitState.valueOf(s);
    }

    /**
     * Part 1: called after each recorded outcome (once the window has enough samples).
     * Drops the breaker OPEN when the rolling success rate falls below the threshold,
     * otherwise keeps/returns it to CLOSED.
     */
    public void evaluateState(String gatewayName, double successRate) {
        if (successRate < threshold) {
            if (getState(gatewayName) != CircuitState.OPEN) {
                logger.warn("Circuit OPEN for {} (successRate {} < threshold {})", gatewayName, successRate, threshold);
            }
            recordState(CircuitState.OPEN, gatewayName);
        } else {
            recordState(CircuitState.CLOSED, gatewayName);
        }
    }

    /**
     * Part 6: when this gateway has been OPEN longer than the cooldown, make it
     * eligible for a probe by moving it to HALF_OPEN. Returns true if it moved.
     */
    public boolean tryHalfOpen(String gatewayName) {
        if (getState(gatewayName) != CircuitState.OPEN) {
            return false;
        }
        String openedAt = redisTemplate.opsForValue().get(openedAtKey(gatewayName));
        if (openedAt == null) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - Long.parseLong(openedAt);
        if (elapsed >= cooldownMs) {
            redisTemplate.opsForValue().set(stateKey(gatewayName), CircuitState.HALF_OPEN.name());
            logger.info("Circuit HALF_OPEN for {} after {}ms cooldown", gatewayName, elapsed);
            return true;
        }
        return false;
    }

    /**
     * Part 6: the outcome of a single probe request sent while HALF_OPEN.
     * Success closes the breaker; failure re-opens it and restarts the cooldown.
     */
    public void recordProbeResult(String gatewayName, boolean success) {
        if (success) {
            recordState(CircuitState.CLOSED, gatewayName);
            logger.info("Probe succeeded for {} -> CLOSED", gatewayName);
        } else {
            recordState(CircuitState.OPEN, gatewayName);
            logger.warn("Probe failed for {} -> OPEN (cooldown restarted)", gatewayName);
        }
    }

    public double getProbeProbability() {
        return probeProbability;
    }
}
