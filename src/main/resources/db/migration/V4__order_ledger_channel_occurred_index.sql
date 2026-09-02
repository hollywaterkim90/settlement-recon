-- 대용량 측정(#7) 2차: V3 로 settlement_line 을 살렸더니 병목이 order_ledger 풀스캔으로 이동.
-- #1 누락·#2 불일치는 원장을 (channel = 배치채널, occurred_at ∈ 배치기간)로 훑는데, 인덱스가 없어
-- seq scan 이었다. 이 인덱스가 그 스캔을 범위(bitmap) 스캔으로 바꿔 #1 을 52→3.5ms, #2 를 47→15ms 로.
-- (#3 유령은 원장을 기간으로 안 훑으므로 이 인덱스와 무관 — PK 로 이미 해결.)
create index idx_order_ledger_channel_occurred
    on order_ledger (channel, occurred_at);
