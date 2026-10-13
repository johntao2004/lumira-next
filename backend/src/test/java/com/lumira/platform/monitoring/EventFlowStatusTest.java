package com.lumira.platform.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lumira.platform.common.DurableEventProducerPort;
import com.lumira.platform.common.DurableEventTopologyPort;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventFlowStatusTest {
    private static final DurableEventTopologyPort.Snapshot CONNECTED =
            new DurableEventTopologyPort.Snapshot(2, 2, 2, List.of());

    @Test
    void distinguishesMissingTopologyFromAnIdleConnectedFlow() {
        assertEquals(
                EventFlowStatus.Code.UNCONNECTED,
                EventFlowStatus.resolve(
                        new DurableEventTopologyPort.Snapshot(0, 0, 0, List.of()), summary()));
        assertEquals(
                EventFlowStatus.Code.UNCONNECTED,
                EventFlowStatus.resolve(
                        new DurableEventTopologyPort.Snapshot(
                                2,
                                1,
                                1,
                                List.of(
                                        new DurableEventProducerPort.EventType(
                                                "feedback", "feedback.created"))),
                        summary()));
        assertEquals(EventFlowStatus.Code.IDLE, EventFlowStatus.resolve(CONNECTED, summary()));
    }

    @Test
    void unknownConsumerIsAnAttentionCondition() {
        assertEquals(
                EventFlowStatus.Code.ATTENTION,
                EventFlowStatus.resolve(
                        new DurableEventTopologyPort.Snapshot(2, 3, 2, List.of()), summary()));
    }

    @Test
    void onlyRecentSuccessfulDeliveryWithNoFaultsIsHealthy() {
        assertEquals(
                EventFlowStatus.Code.HEALTHY,
                EventFlowStatus.resolve(CONNECTED, summary(0, 0, 0, 0, 0, 1, 0, 0)));
        assertEquals(
                EventFlowStatus.Code.PROCESSING,
                EventFlowStatus.resolve(CONNECTED, summary(1, 0, 0, 0, 1, 0, 0, 0)));
        assertEquals(
                EventFlowStatus.Code.PROCESSING,
                EventFlowStatus.resolve(CONNECTED, summary(0, 0, 0, 0, 1, 0, 0, 0)));
    }

    @Test
    void retryDeadLetterAndOverdueBacklogNeedAttention() {
        assertEquals(
                EventFlowStatus.Code.ATTENTION,
                EventFlowStatus.resolve(CONNECTED, summary(0, 0, 1, 0, 0, 0, 0, 0)));
        assertEquals(
                EventFlowStatus.Code.ATTENTION,
                EventFlowStatus.resolve(CONNECTED, summary(0, 0, 0, 1, 0, 0, 0, 0)));
        assertEquals(
                EventFlowStatus.Code.ATTENTION,
                EventFlowStatus.resolve(CONNECTED, summary(0, 0, 0, 0, 0, 0, 1, 0)));
        assertEquals(
                EventFlowStatus.Code.ATTENTION,
                EventFlowStatus.resolve(CONNECTED, summary(0, 0, 0, 0, 0, 0, 0, 1)));
    }

    private static MonitoringRepository.Summary summary() {
        return summary(0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static MonitoringRepository.Summary summary(
            long pending,
            long processing,
            long failed,
            long deadLetter,
            long createdLast5m,
            long deliveredLast5m,
            long retryPending,
            long overdue) {
        return new MonitoringRepository.Summary(
                0,
                0,
                0,
                0,
                0,
                pending,
                failed,
                deadLetter,
                0,
                0,
                0,
                0,
                0,
                0,
                processing,
                createdLast5m,
                deliveredLast5m,
                retryPending,
                overdue,
                OffsetDateTime.now());
    }
}
