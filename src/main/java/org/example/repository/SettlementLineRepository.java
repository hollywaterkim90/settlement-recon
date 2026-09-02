package org.example.repository;

import org.example.entity.SettlementLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SettlementLineRepository extends JpaRepository<SettlementLine, Long> {

    // 유령 정산(#3): 명세엔 있는데 원장에 대응 주문이 없는 줄. #1 누락의 반대 방향.
    //   앵커가 명세다 → 이 배치(:batchId)의 명세 줄만 보고, 원장에 (channel, order_id) 가
    //   하나도 없으면(NOT EXISTS) 유령. 원장 쪽은 배치로 자르지 않는다 — 유령은 순수 키 부재라
    //   period/유예 조건이 없다(그걸 걸면 '다른 창에서 정산된 정상 주문'을 오검출).
    //   부분정산으로 명세가 여러 줄이면 유령도 줄 단위로 다 나온다(dedupe 안 함).
    @Query(value = """
            select s.*
            from settlement_line s
            where s.batch_id = :batchId
              and not exists (
                    select 1 from order_ledger o
                    where s.channel  = o.channel
                      and s.order_id = o.order_id
              )
            """, nativeQuery = true)
    List<SettlementLine> findGhostByBatchId(@Param("batchId") Long batchId);
}
