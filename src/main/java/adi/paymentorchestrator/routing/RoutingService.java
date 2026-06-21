package adi.paymentorchestrator.routing;

import adi.paymentorchestrator.gateway.GatewayAdaptors;
import adi.paymentorchestrator.gateway.dto.GatewayStatus;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import java.util.stream.Collectors;

@Service
public class RoutingService {
     private final Map<String, GatewayAdaptors> gateways;
     private final StringRedisTemplate redisTemplate;

     public RoutingService(List<GatewayAdaptors> adaptorsList, StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
         this.gateways = adaptorsList.stream().
                 collect(Collectors.toMap(GatewayAdaptors::getName, Function.identity()));
     }

     public void recordOutcome(String gatewayName, GatewayStatus status, long latency) {
         String value = status.toString()+":"+latency;
         String key ="gateway:"+gatewayName+":outcome";
         redisTemplate.opsForList().leftPush(key,value);
         redisTemplate.opsForList().trim(key,0,19);
     }

     public GatewayMatrics calForRouting(String  gatewayName) {
         String key ="gateway:"+gatewayName+":outcome";
         List<String> outcomes = redisTemplate.opsForList().range(key,0,-1);
         if(outcomes==null || outcomes.isEmpty())
         {
          return GatewayMatrics.builder().successRate(0.5).latencyRate(200).build();
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
         return GatewayMatrics.builder().successRate(successRate).latencyRate(latencyRate).build();
     }

    @Value("${routing.weight.success}")
    private double w1;

    @Value("${routing.weight.latency}")
    private double w2;

    @Value("${routing.max-latency}")
    private double maxLatency;
     public List<GatewayScore> getGatewayScores() {
        List<GatewayScore> gatewayScores = new ArrayList<>();
        for(String gatewayName:gateways.keySet()){
            GatewayMatrics matrics = calForRouting(gatewayName);
            double latencyScore = Math.max(0,1- matrics.getLatencyRate()/maxLatency);
            double score = w1*matrics.getSuccessRate() + w2*latencyScore;
            gatewayScores.add(new GatewayScore(gatewayName,score));
        }
        return gatewayScores;
     }

    public GatewayAdaptors selectGateway() {
        List<GatewayScore> gatewayScores = getGatewayScores();

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
