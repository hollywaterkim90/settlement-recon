package org.example;

import org.example.entity.OrderLedger;
import org.example.entity.SettlementBatch;
import org.example.entity.SettlementLine;
import org.example.repository.OrderLedgerRepository;
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

// 변이 테스트 (#3): 유령 정산 = 명세엔 있으나 원장에 대응 (channel, order_id) 가 없는 줄.
// #1 MissingDetectionTest 의 거울상 — 앵커가 원장이 아니라 '명세 줄' 이다.
// 함정을 섞어, 쿼리를 조금만 뒤집어도 '정확한 집합' 검증이 빨간불이 되게 설계:
//   - not exists → exists  로 뒤집으면: 정상 매칭 줄이 튀어나오고 유령이 사라짐 → red
//   - 대조에서 s.channel = o.channel 을 지우면: 채널만 다른 원장에 CM 이 매칭돼 유령에서 빠짐 → red
//   - 유예/기간 조건은 애초에 없다(유령은 순수 키 부재) — period 로 자르면 오검출.
@SpringBootTest
@Testcontainers
@Transactional
class GhostSettlementTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired OrderLedgerRepository ledgers;
    @Autowired SettlementBatchRepository batches;
    @Autowired SettlementLineRepository lines;

    @Test
    void detectsExactlyTheSettlementLinesWithNoLedger() {
        // 배치: naver 채널. (유령은 기간과 무관 — batchId 를 얻고 명세를 매달 용도로만 배치를 만든다.)
        SettlementBatch b = new SettlementBatch();
        b.setChannel("naver");
        b.setExternalBatchId("B-1");
        b.setPeriodStart(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
        b.setPeriodEnd(OffsetDateTime.parse("2026-08-08T00:00:00Z"));
        b = batches.saveAndFlush(b);
        Long batchId = b.getId();

        // 원장(진실) — 이게 있으면 그 명세 줄은 유령이 아니다.
        saveLedger("naver", "N1");   // N1 매칭
        saveLedger("naver", "N2");   // N2 매칭(명세는 부분정산 2줄이어도 원장 1건)
        saveLedger("kakao", "CM");   // 채널만 다름 → naver/CM 명세와는 안 맞는다(함정)

        // 명세 줄(배치 B 소속)
        // 유령 → 기대값
        saveLine(batchId, "naver", "G1", "1000.00");                       // 원장 없음
        saveLine(batchId, "naver", "G2", "1000.00");                       // 원장 없음
        saveLine(batchId, "naver", "GP", "600.00");                        // 유령 부분정산 2줄...
        saveLine(batchId, "naver", "GP", "400.00");                        // ...둘 다 유령이라 2줄 다 나와야 함
        saveLine(batchId, "naver", "CM", "1000.00");                       // 원장은 kakao/CM 뿐 → 여전히 유령
        // 정상 매칭 → 유령 아님
        saveLine(batchId, "naver", "N1", "1000.00");                       // 원장 naver/N1 있음
        saveLine(batchId, "naver", "N2", "600.00");                        // 원장 naver/N2 있음(부분정산...
        saveLine(batchId, "naver", "N2", "400.00");                        // ...둘 다 매칭이라 제외)
        lines.flush();

        List<SettlementLine> ghosts = lines.findGhostByBatchId(batchId);

        // GP 가 두 줄이므로 "GP" 를 두 번 기대(줄 단위 반환 — 설계 결정 3 검증).
        assertThat(ghosts).extracting(SettlementLine::getOrderId)
                .containsExactlyInAnyOrder("G1", "G2", "GP", "GP", "CM");
    }

    private void saveLedger(String channel, String orderId) {
        OrderLedger o = new OrderLedger();
        o.setChannel(channel);
        o.setOrderId(orderId);
        o.setAmount(new BigDecimal("1000.00"));
        o.setOccurredAt(OffsetDateTime.parse("2026-08-02T10:00:00Z"));   // 유령 판정엔 안 쓰이지만 NOT NULL.
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
