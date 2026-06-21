package adi.paymentorchestrator.routing;

import adi.paymentorchestrator.gateway.GatewayAdaptors;
import adi.paymentorchestrator.gateway.dto.GatewayStatus;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class TestRoutingService {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ListOperations<String,String> listOps;

    @Mock
    private GatewayAdaptors razorpay;

    @Mock
    private GatewayAdaptors stripe;

    @Mock
    private GatewayAdaptors upi;

    private RoutingService service;

    @BeforeEach
    void setup() {

        when(redisTemplate.opsForList())
                .thenReturn(listOps);

        when(razorpay.getName())
                .thenReturn("razorpay");

        when(stripe.getName())
                .thenReturn("stripe");

        when(upi.getName())
                .thenReturn("upi");

        service = new RoutingService(
                List.of(
                        razorpay,
                        stripe,
                        upi
                ),
                redisTemplate
        );

        ReflectionTestUtils.setField(
                service,
                "w1",
                0.7
        );

        ReflectionTestUtils.setField(
                service,
                "w2",
                0.3
        );

        ReflectionTestUtils.setField(
                service,
                "maxLatency",
                500.0
        );
    }

    //recordOutcome stores data in Redis
    @Test
    void shouldRecordOutcome() {

        service.recordOutcome("razorpay",
                GatewayStatus.SUCCESS,
                120);

        verify(listOps).leftPush(
                "gateway:razorpay:outcome",
                "SUCCESS:120");

        verify(listOps).trim(
                "gateway:razorpay:outcome",
                0,
                19);
    }
    @Test
    void shouldReturnDefaultMetricsWhenNoDataExists() {

        when(listOps.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(Collections.emptyList());

        GatewayMatrics metrics =
                service.calForRouting("razorpay");

        assertEquals(0.5, metrics.getSuccessRate());
        assertEquals(200, metrics.getLatencyRate());
    }
    @Test
    void shouldCalculateMetricsCorrectly() {

        when(listOps.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(List.of(
                        "SUCCESS:100",
                        "SUCCESS:200",
                        "FAILED:300"
                ));

        GatewayMatrics metrics =
                service.calForRouting("razorpay");

        assertEquals(2.0 / 3.0,
                metrics.getSuccessRate(),
                0.001);

        assertEquals(200.0,
                metrics.getLatencyRate(),
                0.001);
    }
    @Test
    void shouldSelectGatewaysAccordingToWeights() {

        Map<String,Integer> counts =
                new HashMap<>();

        for(int i=0;i<1000;i++) {

            GatewayAdaptors gateway =
                    service.selectGateway();

            counts.merge(
                    gateway.getName(),
                    1,
                    Integer::sum
            );
        }

        System.out.println("Gateway Distribution");

        counts.forEach((name,count) -> {
            System.out.println(
                    name + " -> " + count
            );
        });
    }
    @Test
    void shouldFavorBetterPerformingGateway() {

        // Simulate traffic history

        for (int i = 0; i < 20; i++) {

            service.recordOutcome(
                    "razorpay",
                    GatewayStatus.SUCCESS,
                    100
            );
        }

        for (int i = 0; i < 15; i++) {

            service.recordOutcome(
                    "stripe",
                    GatewayStatus.SUCCESS,
                    250
            );
        }

        for (int i = 0; i < 5; i++) {

            service.recordOutcome(
                    "stripe",
                    GatewayStatus.FAILED,
                    250
            );
        }

        for (int i = 0; i < 10; i++) {

            service.recordOutcome(
                    "upi",
                    GatewayStatus.SUCCESS,
                    400
            );
        }

        for (int i = 0; i < 10; i++) {

            service.recordOutcome(
                    "upi",
                    GatewayStatus.FAILED,
                    400
            );
        }

        System.out.println("Scores:");
        service.getGatewayScores()
                .forEach(System.out::println);

        Map<String,Integer> counts = new HashMap<>();

        for (int i = 0; i < 1000; i++) {

            GatewayAdaptors selected =
                    service.selectGateway();

            counts.merge(
                    selected.getName(),
                    1,
                    Integer::sum
            );
        }

        System.out.println("\nDistribution:");

        counts.forEach((k,v) ->
                System.out.println(k + " -> " + v));
    }
}