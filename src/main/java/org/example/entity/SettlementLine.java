package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

// 명세 한 줄. 부분정산으로 (channel, order_id) 가 안 유니크 → 대리키 id 를 PK 로.
// batchId 는 FK(→ settlement_batch)지만, 관계 매핑 없이 값으로만 둔다(지금은 그걸로 충분, YAGNI).
// 원장으로는 FK 를 걸지 않는다 — 유령 정산을 넣을 수 있어야 하므로.
@Entity
@Table(name = "settlement_line")
@Getter
@Setter
@NoArgsConstructor
public class SettlementLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String orderId;

    @Column(precision = 19, scale = 4, nullable = false)
    private BigDecimal settledAmount;
}
