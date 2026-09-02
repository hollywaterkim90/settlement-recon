package org.example;

import org.example.entity.OrderLedger;
import org.example.entity.SettlementBatch;
import org.example.entity.SettlementLine;
import org.example.repository.OrderLedgerRepository;
import org.example.repository.OrderLedgerRepository.AmountMismatch;
import org.example.repository.SettlementBatchRepository;
import org.example.repository.SettlementLineRepository;
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

// 변이 테스트 (#2): 명세 SUM 이 원장 amount 와 다른 주문만 정확히 검출.
// #1/#3 과 달리 '비교'라, 함정도 '경계 근처의 정상'으로 깐다:
//   - EXACT(합이 정확히 일치) / SCALE(scale 만 다르고 값 같음)은 안 나와야 하고,
//     쿼리를 <> → = 로 뒤집으면 이 둘이 튀어나오고 UNDER/OVER 가 사라진다 → red.
//   - NOLINE(명세 없음)은 #1 영역이라 JOIN 에서 빠져야 한다(#2 ≠ #1).
//   - OUTWIN(기간 밖·합 틀림)은 배치 기간 스코프로 걸러져야 한다.
@SpringBootTest
@Testcontainers
@Transactional
class AmountMismatchTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired OrderLedgerRepository ledgers;
    @Autowired SettlementBatchRepository batches;
    @Autowired SettlementLineRepository lines;

    @Test
    void detectsExactlyTheOrdersWhereSettledSumDiffersFromLedger() {
        SettlementBatch b = new SettlementBatch();
        b.setChannel("naver");
        b.setExternalBatchId("B-1");
        b.setPeriodStart(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
        b.setPeriodEnd(OffsetDateTime.parse("2026-08-08T00:00:00Z"));
        b = batches.saveAndFlush(b);
        Long batchId = b.getId();

        String inWindow = "2026-08-02T10:00:00Z";

        // 불일치 → 기대값
        saveLedger("naver", "UNDER", "1000.00", inWindow);   // 원장 1000
        saveLine(batchId, "naver", "UNDER", "900.00");       //   명세 합 900 → 부족
        saveLedger("naver", "OVER", "1000.00", inWindow);    // 원장 1000
        saveLine(batchId, "naver", "OVER", "600.00");        //   명세 합 1100(부분정산)...
        saveLine(batchId, "naver", "OVER", "500.00");        //   ...→ 초과

        // 정상 → 안 나와야 함
        saveLedger("naver", "EXACT", "1000.00", inWindow);   // 원장 1000
        saveLine(batchId, "naver", "EXACT", "600.00");       //   600 + 400 = 1000 → 일치
        saveLine(batchId, "naver", "EXACT", "400.00");
        saveLedger("naver", "SCALE", "1000.0000", inWindow); // 원장 1000.0000
        saveLine(batchId, "naver", "SCALE", "1000.0");       //   scale 만 다름, 값 같음 → 일치(가드)

        // 함정 → 안 나와야 함
        saveLedger("naver", "NOLINE", "1000.00", inWindow);  // 명세 없음 → #1 영역, JOIN 에서 빠짐
        saveLedger("naver", "OUTWIN", "1000.00", "2026-07-30T10:00:00Z"); // 기간 밖
        saveLine(batchId, "naver", "OUTWIN", "500.00");      //   합 틀리지만 기간 밖이라 제외
        lines.flush();

        List<AmountMismatch> mismatches = ledgers.findAmountMismatchByBatchId(batchId);

        assertThat(mismatches).extracting(AmountMismatch::getOrderId)
                .containsExactlyInAnyOrder("UNDER", "OVER");

        // 프로젝션이 원장 amount 와 명세 SUM 을 둘 다 실어오는지 (값 비교, scale 무관).
        AmountMismatch under = mismatches.stream()
                .filter(m -> m.getOrderId().equals("UNDER")).findFirst().orElseThrow();
        assertThat(under.getLedgerAmount()).isEqualByComparingTo("1000.00");
        assertThat(under.getSettledSum()).isEqualByComparingTo("900.00");
    }

    private void saveLedger(String channel, String orderId, String amount, String occurredAt) {
        OrderLedger o = new OrderLedger();
        o.setChannel(channel);
        o.setOrderId(orderId);
        o.setAmount(new BigDecimal(amount));
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
