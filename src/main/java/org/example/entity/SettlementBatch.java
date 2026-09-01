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

import java.time.OffsetDateTime;

// 정산 배치: 어느 채널의, 어느 기간 정산분인가. 기간 [periodStart, periodEnd) 로 원장을 자른다.
@Entity
@Table(name = "settlement_batch")
@Getter
@Setter
@NoArgsConstructor
public class SettlementBatch {

    // bigint generated always as identity ↔ GenerationType.IDENTITY
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String externalBatchId;

    @Column(nullable = false)
    private OffsetDateTime periodStart;

    @Column(nullable = false)
    private OffsetDateTime periodEnd;

    // T+2 유예일수(달력일). 규칙을 데이터로 두는 원칙에 따라 배치가 들고 있는다. 0 = 유예 없음.
    @Column(nullable = false)
    private int graceDays;
}
