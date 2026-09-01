-- 첫 슬라이스: 누락 검출을 위한 3개 테이블.
-- 원장(진실) ↔ 명세(플랫폼이 준 것)를 (channel, order_id)로 대조한다.
-- 두 장부는 서로 FK로 묶지 않는다 — 묶으면 "명세엔 있는데 원장엔 없는" 유령 정산을
-- 넣을 수조차 없어져 검출이 불가능해진다. 대사는 어느 쪽도 완벽하다고 믿지 않는다.

-- 정산 배치: 어느 채널의, 어느 기간 정산분인가.
-- 기간은 '원장'을 자르는 잣대다 (이 기간에 일어난 주문이 이번 정산 대상).
create table settlement_batch (
    id                bigint generated always as identity primary key,
    channel           varchar(64)  not null,
    external_batch_id varchar(128) not null,   -- 플랫폼이 명세에 찍어준 배치 식별자
    period_start      timestamptz  not null,   -- 반열림 구간 [period_start, period_end)
    period_end        timestamptz  not null,
    -- 같은 배치를 두 번 적재하는 것을 막는다.
    constraint uq_batch_channel_external unique (channel, external_batch_id)
);

-- 원장: 우리 쪽 진실. 주문 한 건 = 한 줄. (channel, order_id)로 유일하므로 자연키.
create table order_ledger (
    channel     varchar(64)   not null,
    order_id    varchar(128)  not null,
    amount      numeric(19,4) not null,   -- 돈은 numeric(=decimal). float 금지.
    occurred_at timestamptz   not null,   -- 배치 기간으로 자르려면 필요. tz로 배치 경계 흔들림 방지.
    primary key (channel, order_id)
);

-- 명세: 플랫폼이 정산했다고 준 줄. 부분정산으로 한 주문이 여러 줄일 수 있어
-- (channel, order_id)가 유니크하지 않다 → 대리키(id)를 PK로 둔다.
-- FK는 batch 하나뿐. 원장으로는 걸지 않는다(위 주석 참고).
create table settlement_line (
    id             bigint        generated always as identity primary key,
    batch_id       bigint        not null references settlement_batch(id),
    channel        varchar(64)   not null,
    order_id       varchar(128)  not null,
    settled_amount numeric(19,4) not null
);
