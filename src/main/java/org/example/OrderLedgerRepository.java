package org.example;

import org.example.entity.OrderLedger;
import org.example.entity.OrderLedgerId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderLedgerRepository extends JpaRepository<OrderLedger, OrderLedgerId> {

    // 누락 검출: 배치 B의 기간·채널로 자른 원장 중, 그 배치에 대응하는 명세가
    // 하나도 없는 주문 = 누락.
    // 원장↔명세는 연관관계가 없어 LEFT JOIN 대신 NOT EXISTS 로 표현한다.
    // (부분정산으로 명세가 여러 줄이어도 NOT EXISTS 는 중복 행을 만들지 않는다.)
    @Query("""
            select o from OrderLedger o, SettlementBatch b
            where b.id = :batchId
              and o.channel = b.channel
              and o.occurredAt >= b.periodStart
              and o.occurredAt < b.periodEnd
              and not exists (
                    select 1 from SettlementLine s
                    where s.batchId = :batchId
                      and s.channel = o.channel
                      and s.orderId = o.orderId
              )
            """)
    List<OrderLedger> findMissingByBatchId(@Param("batchId") Long batchId);
}
