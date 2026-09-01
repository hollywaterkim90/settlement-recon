-- #4 T+2 유예: 유예일수를 '규칙 = 데이터' 원칙에 따라 배치가 들고 있는다.
-- 채널·배치마다 유예가 다를 수 있어 전역 설정이 아니라 배치 컬럼에 둔다.
-- 0 = 유예 없음(#1 과 동일). 달력일 기준(영업일은 #5).
alter table settlement_batch
    add column grace_days int not null default 0;
