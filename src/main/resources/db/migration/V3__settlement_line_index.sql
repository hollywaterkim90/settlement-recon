-- 대용량 측정(#7)으로 확인된 병목: settlement_line 을 batch_id 로 훑을 때 seq scan.
-- 배치 하나 조회에 50만 줄을 다 읽어 99%를 버린다(EXPLAIN: Rows Removed by Filter).
-- 이 복합 인덱스가 #1/#2/#3 의 batch_id 스코프와 (channel, order_id) 대조를 함께 살린다.
create index idx_settlement_line_batch_channel_order
    on settlement_line (batch_id, channel, order_id);
