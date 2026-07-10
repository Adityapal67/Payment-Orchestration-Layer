package adi.paymentorchestrator.routing;

import adi.paymentorchestrator.circuit.CircuitService;
import adi.paymentorchestrator.circuit.CircuitState;
import adi.paymentorchestrator.gateway.GatewayAdaptors;
import adi.paymentorchestrator.gateway.dto.GatewayStatus;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import java.util.stream.Collectors;

@Service
public class RoutingService {
     private final Map<String, GatewayAdaptors> gateways;
     private final StringRedisTemplate redisTemplate;
     private final CircuitService circuitService;

     @Value("${circuit.min-samples}")
     private int minSamples;

    @Value("${routing.weight.success}")
    private double w1;

    @Value("${routing.weight.latency}")
    private double w2;

    @Value("${routing.max-latency}")
    private double maxLatency;

     public RoutingService(List<GatewayAdaptors> adaptorsList,
                           StringRedisTemplate redisTemplate,
                           CircuitService circuitService) {
        this.redisTemplate = redisTemplate;
        this.circuitService = circuitService;
         this.gateways = adaptorsList.stream().
                 collect(Collectors.toMap(GatewayAdaptors::getName, Function.identity()));
     }

     /**
      * Records the outcome of a gateway call into a rolling window (last 20) and
      * feeds it to the circuit breaker.
      *  - If the gateway is currently HALF_OPEN, this call was a probe -> settle the breaker.
      *  - Otherwise, once we have enough samples, re-evaluate OPEN/CLOSED.
      */
     public void recordOutcome(String gatewayName, GatewayStatus status, long latency) {
         String value = status.toString()+":"+latency;
         String key ="gateway:"+gatewayName+":outcome";
         redisTemplate.opsForList().leftPush(key,value);
         redisTemplate.opsForList().trim(key,0,19);

         if (circuitService.getState(gatewayName) == CircuitState.HALF_OPEN) {
             circuitService.recordProbeResult(gatewayName, status == GatewayStatus.SUCCESS);
             return;
         }

         GatewayMatrics matrics = calForRouting(gatewayName);
         if (matrics.getSampleCount() >= minSamples) {
             circuitService.evaluateState(gatewayName, matrics.getSuccessRate());
         }
     }

     public GatewayMatrics calForRouting(String  gatewayName) {
         String key ="gateway:"+gatewayName+":outcome";
         List<String> outcomes = redisTemplate.opsForList().range(key,0,-1);
         if(outcomes==null || outcomes.isEmpty())
         {
          return GatewayMatrics.builder().successRate(0.5).latencyRate(200).sampleCount(0).build();
         }
         int succesCount= 0;
         long latencyCount = 0;
         for(String outcome:outcomes){
             String[] parts = outcome.split(":");
             String status = parts[0];
             long latency = Long.parseLong(parts[1]);
             if("SUCCESS".equals(status)){
                 succesCount++;
             }
            latencyCount += latency;
         }
         double successRate = (double) succesCount/outcomes.size();
         double latencyRate = (double) latencyCount/outcomes.size();
         return GatewayMatrics.builder()
                 .successRate(successRate)
                 .latencyRate(latencyRate)
                 .sampleCount(outcomes.size())
                 .build();
     }

     public List<GatewayScore> getGatewayScores() {
         return getGatewayScores(Collections.emptySet());
     }

     /**
      * Scores every eligible gateway. A gateway is excluded when:
      *   - it is in the {@code excluded} set (e.g. a failed gateway during failover), or
      *   - its circuit is OPEN, or
      *   - its circuit is HALF_OPEN and it loses the probe-probability roll this round.
      * A HALF_OPEN gateway that wins the roll is included so it can receive a probe request.
      */
    public List<GatewayScore> getGatewayScores(Set<String> excluded) {
        List<GatewayScore> gatewayScores = new ArrayList<>();
        for(String gatewayName:gateways.keySet()){
            if (excluded.contains(gatewayName)) {
                continue;
            }
            CircuitState state = circuitService.getState(gatewayName);
            if (state == CircuitState.OPEN) {
                continue;
            }
            if (state == CircuitState.HALF_OPEN && Math.random() >= circuitService.getProbeProbability()) {
                continue;
            }
            GatewayMatrics matrics = calForRouting(gatewayName);
            double latencyScore = Math.max(0,1- matrics.getLatencyRate()/maxLatency);
            double score = w1*matrics.getSuccessRate() + w2*latencyScore;
            gatewayScores.add(new GatewayScore(gatewayName,score));
        }
        return gatewayScores;
     }

    public GatewayAdaptors selectGateway() {
        return selectGateway(Collections.emptySet());
    }

    /**
     * Weighted-random pick over the eligible gateways (score = selection weight),
     * excluding any gateway names passed in {@code excluded}.
     */
    public GatewayAdaptors selectGateway(Set<String> excluded) {
        List<GatewayScore> gatewayScores = getGatewayScores(excluded);

        if (gatewayScores.isEmpty()) {
            throw new IllegalStateException("No gateways available for routing");
        }

        double totalScore = gatewayScores.stream()
                .mapToDouble(GatewayScore::getScore)
                .sum();

        double roll = Math.random() * totalScore;

        double runningTotal = 0;
        for (GatewayScore gs : gatewayScores) {
            runningTotal += gs.getScore();
            if (roll < runningTotal) {
                return gateways.get(gs.getGatewayName());
            }
        }

        // Fallback safety net — floating point edge case where roll == totalScore exactly
        return gateways.get(gatewayScores.get(gatewayScores.size() - 1).getGatewayName());
    }
}
