package org.example;

import org.example.entity.OrderLedger;
import org.example.entity.SettlementBatch;
import org.example.repository.OrderLedgerRepository;
import org.example.repository.SettlementBatchRepository;
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

// 변이 테스트 (#4): T+2 유예 경계.
// 명세가 하나도 없는(= #1 이면 전부 누락) 원장만 넣고, 유예 때문에 '아직 안 온' 건은
// 누락에서 빠지는지 본다. 경계 = period_end - graceDays.
// graceDays=2, period_end=08-08T00:00Z → 컷오프 = 08-06T00:00Z.
//   컷오프보다 이르면(strict '<') 누락, 컷오프 이후(유예 중)면 제외.
// 쿼리를 조금만 뒤집어도(‘<’→‘<=’, 유예 줄 삭제) 경계 케이스가 빨간불이 되게 설계.
@SpringBootTest
@Testcontainers
@Transactional
class T2SettlementWindowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired OrderLedgerRepository ledgers;
    @Autowired SettlementBatchRepository batches;

    @Test
    void graceWindowExcludesNotYetDueOrders() {
        SettlementBatch b = new SettlementBatch();
        b.setChannel("naver");
        b.setExternalBatchId("B-1");
        b.setPeriodStart(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
        b.setPeriodEnd(OffsetDateTime.parse("2026-08-08T00:00:00Z"));   // 컷오프 = 08-06T00:00Z (grace=2)
        b.setGraceDays(2);   // 유예일수는 배치가 데이터로 들고 있다.
        b = batches.saveAndFlush(b);
        Long batchId = b.getId();

        // 유예 지남 → 누락 (기대값)
        saveLedger("naver", "OLD", "2026-08-02T10:00:00Z");        // 한참 전
        saveLedger("naver", "EDGE_IN", "2026-08-05T23:59:59Z");    // 컷오프 직전(1초) → 누락
        // 유예 중 → 누락 아님
        saveLedger("naver", "EDGE_GRACE", "2026-08-06T00:00:00Z"); // 컷오프 정각 → 유예(strict '<')
        saveLedger("naver", "RECENT", "2026-08-07T10:00:00Z");     // 마감 직전 발생 → 유예
        ledgers.flush();

        List<OrderLedger> missing = ledgers.findMissingByBatchId(batchId);

        assertThat(missing).extracting(OrderLedger::getOrderId)
                .containsExactlyInAnyOrder("OLD", "EDGE_IN");
    }

    private void saveLedger(String channel, String orderId, String occurredAt) {
        OrderLedger o = new OrderLedger();
        o.setChannel(channel);
        o.setOrderId(orderId);
        o.setAmount(new BigDecimal("1000.00"));
        o.setOccurredAt(OffsetDateTime.parse(occurredAt));
        ledgers.save(o);
    }
}
