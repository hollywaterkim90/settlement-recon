package org.example.repository;

import org.example.entity.OrderLedger;
import org.example.entity.OrderLedgerId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface OrderLedgerRepository extends JpaRepository<OrderLedger, OrderLedgerId> {

    // 하는 일: 한 배치(:batchId)에서 "받았어야 하는데 명세에 안 찍힌" 주문을 찾는다.
    //   - 그 배치의 채널·기간에 드는 원장 주문만 본다.
    //   - 거기에 대응하는 명세 줄이 하나도 없으면 = 누락.
    //   (별칭: 배치 = b, 원장 = o, 명세 = s)
    //
    // 왜 NOT EXISTS 인가: 원장과 명세는 서로 연결이 없어 JOIN 으로 못 묶는다.
    //   그래서 "이 주문에 명세가 없다"를 NOT EXISTS 로 묻는다.
    //   부분정산으로 명세가 여러 줄이어도 결과가 뻥튀기되지 않는 것도 이점.
    //
    // T+2 유예(#4): "아직 정산될 때가 안 된 건"을 누락으로 잘못 잡지 않기.
    //   정산은 거래하고 며칠 뒤 들어온다. 그 유예일수(달력일)는 배치가 데이터로 들고 있다(b.grace_days).
    //   그래서 (거래일 + grace_days) 가 배치 마감일(period_end)을 아직 안 넘겼으면,
    //   명세가 없는 게 정상이라 누락에서 뺀다.
    //     조건: occurred_at < period_end - grace_days   (grace_days=0 이면 유예 없음 = #1 과 같음)
    //   마감 시각 딱 그 순간은 아직 유예로 본다(그래서 '<'). 배치 기간이 끝시각을
    //   포함하지 않는 것과 방향을 맞췄다.
    //
    // 날짜 빼기(period_end - 며칠)가 표준 JPQL 로는 깔끔히 안 돼서 Postgres 전용 SQL 로 썼다.
    @Query(value = """
            select o.*
            from order_ledger o
            join settlement_batch b on b.id = :batchId
            where o.channel = b.channel
              and o.occurred_at >= b.period_start
              and o.occurred_at <  b.period_end
              and o.occurred_at <  b.period_end - make_interval(days => b.grace_days)
              and not exists (
                    select 1 from settlement_line s
                    where s.batch_id = :batchId
                      and s.channel  = o.channel
                      and s.order_id = o.order_id
              )
            """, nativeQuery = true)
    List<OrderLedger> findMissingByBatchId(@Param("batchId") Long batchId);

    // 금액 불일치(#2): 명세는 있는데 (channel, order_id)별 SUM(settled_amount) 이 원장 amount 와 다른 주문.
    //   #1/#3 이 '존재/부재'였다면 이건 '비교'다 → 집계(GROUP BY … SUM)가 들어간다.
    //   - settlement_line 을 (channel, order_id)로 JOIN → '명세 있는 주문'만 남는다.
    //     명세 없는 주문(=#1 누락)은 조인에서 빠지므로 #2 와 겹치지 않는다.
    //   - 유예(#4)는 안 넣는다: 순수 합-비교. '유예 중 부족정산은 진행 중'은 파생 이슈로 분리.
    //   - having 의 o.amount <> SUM(...). Postgres numeric 의 <> 는 값 비교(1000.00 = 1000.0000)라
    //     부호 무관 부족·초과를 다 잡고 BigDecimal.equals 의 scale 함정도 안 밟는다.
    //   - 별칭을 큰따옴표로 감싼 이유: Postgres 는 안 감싼 식별자를 소문자로 접어(orderId→orderid)
    //     인터페이스 프로젝션 getter 매칭이 깨진다. 감싸서 카멜케이스를 보존한다.
    @Query(value = """
            select o.channel      as "channel",
                   o.order_id     as "orderId",
                   o.amount       as "ledgerAmount",
                   sum(s.settled_amount) as "settledSum"
            from order_ledger o
            join settlement_batch b on b.id = :batchId
            join settlement_line s
                  on s.batch_id = :batchId
                 and s.channel  = o.channel
                 and s.order_id = o.order_id
            where o.channel = b.channel
              and o.occurred_at >= b.period_start
              and o.occurred_at <  b.period_end
            group by o.channel, o.order_id, o.amount
            having o.amount <> sum(s.settled_amount)
            """, nativeQuery = true)
    List<AmountMismatch> findAmountMismatchByBatchId(@Param("batchId") Long batchId);

    // 조회 결과 모양(프로젝션): 어느 테이블 행도 아닌, 원장 amount + 명세 SUM 을 조합한 값.
    // getter 이름 = 쿼리 별칭. diff 는 안 담고 쓰는 쪽에서 ledgerAmount - settledSum.
    interface AmountMismatch {
        String getChannel();
        String getOrderId();
        BigDecimal getLedgerAmount();
        BigDecimal getSettledSum();
    }
}
