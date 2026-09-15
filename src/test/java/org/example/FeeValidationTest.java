package org.example;

import jakarta.persistence.EntityManager;
import org.example.entity.OrderLedger;
import org.example.entity.SettlementBatch;
import org.example.entity.SettlementLine;
import org.example.repository.OrderLedgerRepository;
import org.example.repository.OrderLedgerRepository.FeeAwareMismatch;
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

// 변이 테스트 (#8): 명세 SUM 이 '원장 − 수수료'(기대 순액)와 다른 주문만 정확히 검출.
// #2 가 "명세 합 = 원장"을 봤다면 여기선 기준이 기대 순액으로 바뀐다. 그래서 함정도 세 갈래다:
//   ① 수수료를 뗀 정상 정산  → 안 나와야 한다. #2 라면 전부 나온다(그게 #8 의 존재 이유).
//   ② 반올림 경계          → trunc 를 round 로 바꾸면 결과가 1원 갈리도록 금액을 고른다.
//   ③ 규칙 없는 채널        → LEFT JOIN + coalesce(rate,0) 라 검출이 계속 돌아야 한다.
//                           INNER 로 바꾸면 채널 전체가 조용히 사라진다 → red.
//
// fee_rule 은 프로덕션 코드가 네이티브 쿼리로만 읽으므로 엔티티를 두지 않았다.
// 테스트 시드도 같은 이유로 EntityManager 네이티브 INSERT 로 넣는다(쓰지 않을 엔티티를 만들지 않는다).
@SpringBootTest
@Testcontainers
@Transactional
class FeeValidationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired OrderLedgerRepository ledgers;
    @Autowired SettlementBatchRepository batches;
    @Autowired SettlementLineRepository lines;
    @Autowired EntityManager em;

    private static final String IN_WINDOW = "2026-08-02T10:00:00Z";

    // 요율이 있는 채널: 기대값이 '원장 − 수수료'로 바뀐다.
    //   naver 3.3% → 10,000 의 수수료는 330, 기대 순액 9,670.
    @Test
    void detectsOrdersWhereSettledSumDiffersFromExpectedNet() {
        saveFeeRule("naver", "0.033000");
        Long batchId = saveBatch("naver", "B-1");

        // 불일치 → 나와야 함
        saveLedger("naver", "UNDER", "10000.00", IN_WINDOW);   // 기대 9,670
        saveLine(batchId, "naver", "UNDER", "9000.00");        //   670 부족
        saveLedger("naver", "OVER", "10000.00", IN_WINDOW);    // 기대 9,670
        saveLine(batchId, "naver", "OVER", "5000.00");         //   부분정산 합 9,800...
        saveLine(batchId, "naver", "OVER", "4800.00");         //   ...→ 130 초과

        // 정상 → 안 나와야 함
        saveLedger("naver", "EXACT_NET", "10000.00", IN_WINDOW);
        saveLine(batchId, "naver", "EXACT_NET", "9670.00");    // 수수료 330 뗀 정상 정산
        saveLedger("naver", "SCALE", "10000.0000", IN_WINDOW);
        saveLine(batchId, "naver", "SCALE", "9670.0");         // scale 만 다름 → numeric <> 는 값 비교

        // 함정 → 안 나와야 함
        saveLedger("naver", "NOLINE", "10000.00", IN_WINDOW);  // 명세 없음 → #1 영역, JOIN 에서 빠짐
        saveLedger("naver", "OUTWIN", "10000.00", "2026-07-30T10:00:00Z");
        saveLine(batchId, "naver", "OUTWIN", "1.00");          // 합 틀리지만 기간 밖
        lines.flush();

        List<FeeAwareMismatch> found = ledgers.findFeeAwareMismatchByBatchId(batchId);

        assertThat(found).extracting(FeeAwareMismatch::getOrderId)
                .containsExactlyInAnyOrder("UNDER", "OVER");

        // 프로젝션이 원인 판별에 필요한 값을 다 실어오는지: 적용 요율과 기대 순액.
        FeeAwareMismatch under = pick(found, "UNDER");
        assertThat(under.getLedgerAmount()).isEqualByComparingTo("10000.00");
        assertThat(under.getAppliedRate()).isEqualByComparingTo("0.033000");
        assertThat(under.getExpectedNet()).isEqualByComparingTo("9670");
        assertThat(under.getSettledSum()).isEqualByComparingTo("9000.00");
    }

    // 반올림 경계: trunc(버림)로 고정한 결정을 지키는 가드.
    //   10,050 × 0.033 = 331.65 → trunc 331(기대 9,719) / round 332(기대 9,718).
    //   금액을 10,000 으로 잡으면 330.0 이라 두 함수가 같은 답을 내서 함정을 못 잡는다.
    @Test
    void truncatesFeeInsteadOfRounding() {
        saveFeeRule("naver", "0.033000");
        Long batchId = saveBatch("naver", "B-2");

        // 버림 기준으로 정상 → 안 나와야 한다. round 로 바꾸면 기대가 9,718 이 되어 튀어나온다.
        saveLedger("naver", "TRUNC_OK", "10050.00", IN_WINDOW);
        saveLine(batchId, "naver", "TRUNC_OK", "9719.00");

        // 반올림 기준에 맞춘 금액 → 버림 기준으로는 1원 부족이라 나와야 한다.
        //   round 로 바꾸면 이게 정상이 되어 사라진다. 위아래 둘이 함께 뒤집힌다.
        saveLedger("naver", "ROUND_BAD", "10050.00", IN_WINDOW);
        saveLine(batchId, "naver", "ROUND_BAD", "9718.00");
        lines.flush();

        List<FeeAwareMismatch> found = ledgers.findFeeAwareMismatchByBatchId(batchId);

        assertThat(found).extracting(FeeAwareMismatch::getOrderId)
                .containsExactly("ROUND_BAD");
        assertThat(pick(found, "ROUND_BAD").getExpectedNet()).isEqualByComparingTo("9719");
    }

    // 요율이 채널마다 다르다는 것(= 규칙이 코드가 아니라 데이터라는 것)을 지키는 가드.
    //   같은 10,000 이라도 kakao 2% 면 기대 순액이 9,800 이다.
    //   naver 기준 정상액(9,670)을 kakao 에 넣으면 불일치로 잡혀야 한다.
    @Test
    void appliesRatePerChannel() {
        saveFeeRule("naver", "0.033000");
        saveFeeRule("kakao", "0.020000");
        Long kakaoBatch = saveBatch("kakao", "B-3");

        saveLedger("kakao", "KAKAO_OK", "10000.00", IN_WINDOW);
        saveLine(kakaoBatch, "kakao", "KAKAO_OK", "9800.00");      // 2% → 정상

        saveLedger("kakao", "NAVER_RATE", "10000.00", IN_WINDOW);
        saveLine(kakaoBatch, "kakao", "NAVER_RATE", "9670.00");    // naver 요율을 쓴 값 → 불일치
        lines.flush();

        List<FeeAwareMismatch> found = ledgers.findFeeAwareMismatchByBatchId(kakaoBatch);

        assertThat(found).extracting(FeeAwareMismatch::getOrderId)
                .containsExactly("NAVER_RATE");
        assertThat(pick(found, "NAVER_RATE").getAppliedRate()).isEqualByComparingTo("0.020000");
        assertThat(pick(found, "NAVER_RATE").getExpectedNet()).isEqualByComparingTo("9800");
    }

    // 규칙이 없는 채널: LEFT JOIN + coalesce(rate, 0) 라 수수료 0 으로 보고 검출이 계속 돈다.
    //   INNER JOIN 으로 바꾸면 이 채널이 통째로 빠져 UNSET_BAD 가 사라진다 → red.
    //   규칙 데이터 누락이 '검출 0건'으로 보이는 게 대사에서 가장 위험한 실패라 이 가드가 필요하다.
    @Test
    void stillDetectsOnChannelsWithoutFeeRule() {
        // fee_rule 에 'toss' 행을 넣지 않는다.
        Long batchId = saveBatch("toss", "B-4");

        saveLedger("toss", "UNSET_OK", "5000.00", IN_WINDOW);
        saveLine(batchId, "toss", "UNSET_OK", "5000.00");   // 수수료 0 → 정상

        saveLedger("toss", "UNSET_BAD", "5000.00", IN_WINDOW);
        saveLine(batchId, "toss", "UNSET_BAD", "4500.00");  // 500 부족 → 검출돼야 한다
        lines.flush();

        List<FeeAwareMismatch> found = ledgers.findFeeAwareMismatchByBatchId(batchId);

        assertThat(found).extracting(FeeAwareMismatch::getOrderId)
                .containsExactly("UNSET_BAD");
        assertThat(pick(found, "UNSET_BAD").getAppliedRate()).isEqualByComparingTo("0");
        assertThat(pick(found, "UNSET_BAD").getExpectedNet()).isEqualByComparingTo("5000");
    }

    private FeeAwareMismatch pick(List<FeeAwareMismatch> found, String orderId) {
        return found.stream().filter(m -> m.getOrderId().equals(orderId)).findFirst().orElseThrow();
    }

    private void saveFeeRule(String channel, String rate) {
        em.createNativeQuery("insert into fee_rule(channel, rate) values (:c, cast(:r as numeric))")
                .setParameter("c", channel)
                .setParameter("r", rate)
                .executeUpdate();
    }

    private Long saveBatch(String channel, String externalBatchId) {
        SettlementBatch b = new SettlementBatch();
        b.setChannel(channel);
        b.setExternalBatchId(externalBatchId);
        b.setPeriodStart(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
        b.setPeriodEnd(OffsetDateTime.parse("2026-08-08T00:00:00Z"));
        return batches.saveAndFlush(b).getId();
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
