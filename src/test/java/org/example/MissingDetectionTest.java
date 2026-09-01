package org.example;

import org.example.entity.OrderLedger;
import org.example.entity.SettlementBatch;
import org.example.entity.SettlementLine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 변이 테스트: 원장 중 명세에 줄이 하나도 없는 주문만 정확히 검출하는지 본다.
// 함정 행(명세 있음 / 기간 밖 / 다른 채널 / 부분정산 2줄)을 섞어, 쿼리를 조금만
// 망가뜨려도 '정확한 집합' 검증이 빨간불이 되도록 설계했다.
@SpringBootTest
@Testcontainers
@Transactional
class MissingDetectionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired OrderLedgerRepository ledgers;
    @Autowired SettlementBatchRepository batches;
    @Autowired SettlementLineRepository lines;

    @Test
    void detectsExactlyTheOrdersWithNoSettlementLine() {
        // 배치: naver 채널, [08-01, 08-08) 반열림
        SettlementBatch b = new SettlementBatch();
        b.setChannel("naver");
        b.setExternalBatchId("B-1");
        b.setPeriodStart(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
        b.setPeriodEnd(OffsetDateTime.parse("2026-08-08T00:00:00Z"));
        b = batches.saveAndFlush(b);
        Long batchId = b.getId();

        // 기간 안, 명세 없음 → 누락 (기대값)
        saveLedger("naver", "M1", "2026-08-02T10:00:00Z");
        saveLedger("naver", "M2", "2026-08-03T10:00:00Z");
        // 함정들 → 누락 아님
        saveLedger("naver", "S1", "2026-08-02T11:00:00Z");   // 명세 있음
        saveLedger("naver", "S2", "2026-08-04T09:00:00Z");   // 명세 2줄(부분정산)
        saveLedger("naver", "OUT1", "2026-07-30T10:00:00Z"); // 기간 밖
        saveLedger("kakao", "K1", "2026-08-02T10:00:00Z");   // 다른 채널

        // 명세 줄 (batch B 소속)
        saveLine(batchId, "naver", "S1", "1000.00");
        saveLine(batchId, "naver", "S2", "600.00");
        saveLine(batchId, "naver", "S2", "400.00");
        lines.flush();

        List<OrderLedger> missing = ledgers.findMissingByBatchId(batchId);

        assertThat(missing).extracting(OrderLedger::getOrderId)
                .containsExactlyInAnyOrder("M1", "M2");
    }

    private void saveLedger(String channel, String orderId, String occurredAt) {
        OrderLedger o = new OrderLedger();
        o.setChannel(channel);
        o.setOrderId(orderId);
        o.setAmount(new BigDecimal("1000.00"));
        o.setOccurredAt(OffsetDateTime.parse(occurredAt));
        ledgers.save(o);
    }

    private void saveLine(Long batchId, String channel, String orderId, String amount) {
        SettlementLine s = new SettlementLine();
        s.setBatchId(batchId);
        s.setChannel(channel);
        s.setOrderId(orderId);
        s.setSettledAmount(new BigDecimal(amount));
        lines.save(s);
    }
}
