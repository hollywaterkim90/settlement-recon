package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// 원장(우리 쪽 진실). (channel, order_id) 자연키 → @IdClass 로 두 필드를 그대로 키로.
@Entity
@Table(name = "order_ledger")
@IdClass(OrderLedgerId.class)
@Getter
@Setter
@NoArgsConstructor
public class OrderLedger {

    @Id
    private String channel;

    @Id
    private String orderId;

    // numeric(19,4) ↔ BigDecimal. float 아님.
    @Column(precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    // timestamptz ↔ OffsetDateTime. LocalDateTime 은 tz 를 버리니 쓰지 않는다.
    @Column(nullable = false)
    private OffsetDateTime occurredAt;
}
