package org.example.repository;

import org.example.entity.OrderLedger;
import org.example.entity.OrderLedgerId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
