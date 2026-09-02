-- 측정용(벤치마크) 데이터 생성 — Flyway 아님(일회성). docker 의 Postgres 에 직접 적재.
--
-- 정답을 우리가 쥔다: i = 주문 일련번호 1..500,000, 배치 5,000개씩 100묶음.
--   i % 100 = 0   → 누락(명세 줄 없음)          배치당 50, 총 5,000
--   i % 100 = 50  → 불일치(줄 1개, 900)          배치당 50, 총 5,000
--   i % 100 = 20  → 정상 부분정산(600 + 400)      배치당 50, 총 5,000
--   그 외          → 정상(줄 1개, 1000)
--   유령           → 원장에 없는 GHOST 주문 5,000줄(배치당 50)
-- => 한 배치 조회 시 기대: 누락 50 · 불일치 50 · 유령 50.

-- 0) 깨끗이 (identity 도 리셋)
truncate settlement_line, order_ledger, settlement_batch restart identity cascade;

-- 1) 배치 100개 — 하루짜리 창을 100일로 나눠 안 겹치게
insert into settlement_batch (channel, external_batch_id, period_start, period_end, grace_days)
select 'naver',
       'B-' || b,
       timestamptz '2026-01-01 00:00:00+00' + (b - 1) * interval '1 day',
       timestamptz '2026-01-01 00:00:00+00' +  b      * interval '1 day',
       0
from generate_series(1, 100) as b;

-- 2) 원장 50만 — 배치당 5,000, occurred_at 은 그 배치 창(하루) 안에 흩뿌림
insert into order_ledger (channel, order_id, amount, occurred_at)
select 'naver',
       'ORD-' || i,
       1000.00,
       timestamptz '2026-01-01 00:00:00+00'
         + ((i - 1) / 5000) * interval '1 day'      -- 며칠째 배치인가
         + ((i - 1) % 5000) * interval '1 second'   -- 창 안에서 흩뿌리기(5000초 < 하루)
from generate_series(1, 500000) as i;

-- 3a) 명세: 정상 단일 줄(1000) + 불일치(줄 1개, 900). 누락(=0)·부분정산(=20)은 제외.
insert into settlement_line (batch_id, channel, order_id, settled_amount)
select b.id, 'naver', 'ORD-' || i,
       case when i % 100 = 50 then 900.00 else 1000.00 end
from generate_series(1, 500000) as i
join settlement_batch b on b.external_batch_id = 'B-' || (((i - 1) / 5000) + 1)
where i % 100 <> 0
  and i % 100 <> 20;

-- 3b) 명세: 정상 부분정산 두 줄(600 + 400) — i % 100 = 20
insert into settlement_line (batch_id, channel, order_id, settled_amount)
select b.id, 'naver', 'ORD-' || i, amt
from generate_series(1, 500000) as i
join settlement_batch b on b.external_batch_id = 'B-' || (((i - 1) / 5000) + 1)
cross join (values (600.00), (400.00)) as parts(amt)
where i % 100 = 20;

-- 3c) 유령 5,000 — 원장에 없는 주문. 배치엔 매달아야 하니 batch_id 부여.
insert into settlement_line (batch_id, channel, order_id, settled_amount)
select b.id, 'naver', 'GHOST-' || g, 1000.00
from generate_series(1, 5000) as g
join settlement_batch b on b.external_batch_id = 'B-' || ((g % 100) + 1);

-- 4) 통계 갱신(EXPLAIN 이 정확한 계획을 세우도록)
analyze settlement_batch;
analyze order_ledger;
analyze settlement_line;

-- 5) 정답 확인용 (원하면 실행): 배치 1개 기준 50/50/50 이 나와야 함
-- select count(*) from order_ledger;                                  -- 500000
-- select count(*) from settlement_line;                              -- 505000 (단일 490000 + 부분정산 10000 + 유령 5000)
-- select id from settlement_batch order by id limit 1;               -- 조회할 batchId
