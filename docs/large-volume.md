# 대용량 테스트 (bench) — 설명과 결과

이슈 #7의 실측 기록. "대용량 전제(설계원칙 4)"를 말이 아니라 **EXPLAIN 증거로** 남긴다.
생성 스크립트: `scripts/bench-load.sql` · 인덱스: `V3` (측정 후 추가).

> **기록 규칙**: 이후 측정도 이 문서에 **이어서** 남긴다 — 매번 (전/후 실행계획 · 시간 · 버퍼 · 배운 것).

## 무엇을 하나

검출 쿼리 3종(#1 누락 · #2 금액불일치 · #3 유령)을 **50만 건**짜리 합성 데이터에 돌려,
- 인덱스 **없을 때** 실행계획을 보고(어디서 느린가),
- 인덱스 **넣은 뒤** 다시 봐서(무엇이 바뀌나) 전/후를 비교한다.

## 데이터 만들기 — `scripts/bench-load.sql`

`generate_series(1, N)` = "1부터 N까지 숫자를 한 줄에 하나씩" 뱉는 발생기. 이 숫자 하나 = 정산 대상 거래 한 건.

- **배치 100개** — 하루짜리 창을 100일로 안 겹치게 나눔.
- **원장 50만** — 거래 `i`를 나눗셈으로 배치 창에 담음:
  `(i-1)/5000` = 며칠째(배치), `(i-1)%5000` = 창 안 몇 초. → i=1~5000은 B-1, 5001~10000은 B-2 …
- **명세** — 거래번호 끝 두 자리(`i % 100`)로 이상을 심어 **개수를 우리가 쥔다**:

  | `i % 100` | 무엇 | 명세 | 배치당 | 총 |
  |---|---|---|---|---|
  | 0 | 누락 | 줄 없음 | 50 | 5,000 |
  | 50 | 불일치 | 1줄, 900 (원장 1000과 다름) | 50 | 5,000 |
  | 20 | 정상 부분정산 | 2줄, 600+400=1000 | 50 | 5,000 |
  | 그 외 | 정상 | 1줄, 1000 | — | 485,000 |
  | (별도) | 유령 | 원장에 없는 GHOST 주문 | 50 | 5,000 |

  → 적재 결과: 원장 500,000 · 명세 505,000 · 배치 100.

### 왜 배치를 100개로 쪼갰나 (벤치의 성패)

인덱스는 "**한 배치 조회할 때 나머지를 걸러내는**" 걸로 값을 한다. 만약 명세 전부가 **한 배치**면
그 배치 조회 = 전체 조회라 **seq scan(풀스캔)이 오히려 최적** — 인덱스가 효과를 못 보여준다.
100개로 쪼개 한 배치 = 전체의 1%로 만들어야, 인덱스가 99%를 프루닝할 값어치가 생긴다.

## 측정 방법

`EXPLAIN (ANALYZE, BUFFERS)`로 `batchId = 1` 조회. 검출 카운트도 같이 확인(대량에서도 정답 유지):
**배치 1 → 누락 50 · 불일치 50 · 유령 50** (기대치와 일치).

### 실행한 EXPLAIN (재현용)

각 라운드마다 아래 3개를 그대로 돌렸다(`batchId = 1`). 라운드 사이엔 인덱스만 추가.

```sql
-- #1 누락
EXPLAIN (ANALYZE, BUFFERS, TIMING OFF)
select o.* from order_ledger o join settlement_batch b on b.id = 1
where o.channel = b.channel and o.occurred_at >= b.period_start and o.occurred_at < b.period_end
  and o.occurred_at < b.period_end - make_interval(days => b.grace_days)
  and not exists (select 1 from settlement_line s
                  where s.batch_id = 1 and s.channel = o.channel and s.order_id = o.order_id);

-- #2 금액 불일치
EXPLAIN (ANALYZE, BUFFERS, TIMING OFF)
select o.channel, o.order_id, o.amount, sum(s.settled_amount)
from order_ledger o join settlement_batch b on b.id = 1
join settlement_line s on s.batch_id = 1 and s.channel = o.channel and s.order_id = o.order_id
where o.channel = b.channel and o.occurred_at >= b.period_start and o.occurred_at < b.period_end
group by o.channel, o.order_id, o.amount having o.amount <> sum(s.settled_amount);

-- #3 유령
EXPLAIN (ANALYZE, BUFFERS, TIMING OFF)
select s.* from settlement_line s where s.batch_id = 1
  and not exists (select 1 from order_ledger o
                  where o.channel = s.channel and o.order_id = s.order_id);
```

라운드 사이 인덱스 추가:
```sql
create index idx_settlement_line_batch_channel_order on settlement_line (batch_id, channel, order_id);  -- 라운드 2 전
create index idx_order_ledger_channel_occurred        on order_ledger (channel, occurred_at);            -- 라운드 3 전
```

## 결과 — 인덱스 없음

세 쿼리 **모두** `settlement_line`을 **Parallel Seq Scan**으로 훑고 `batch_id=1`만 남긴다:

```
Parallel Seq Scan on settlement_line s
  Filter: (batch_id = 1)
  Rows Removed by Filter: 166650     ← 워커당 16.6만 버리고(×3 ≈ 50만) 배치1의 ~5천만 건짐
```

| 쿼리 | 실행시간 | settlement_line | order_ledger 쪽 |
|---|---|---|---|
| #1 누락 | 52 ms | Seq Scan (batch_id 버림) | Seq Scan (채널+기간) |
| #2 불일치 | 47 ms | Seq Scan (batch_id 버림) | Seq Scan (채널+기간) |
| #3 유령 | 19 ms | Seq Scan (batch_id 버림) | **Index Only Scan (PK)** |

측정으로 확인된 것:
1. **주범 = `settlement_line`의 `batch_id` 필터 seq scan.** 배치 하나 뽑는데 50만 줄을 다 읽어 99%를 버린다.
   → `settlement_line(batch_id, channel, order_id)` 인덱스가 정확히 이걸 겨냥.
2. **#3의 원장 쪽은 이미 `Index Only Scan`** — 원장 PK가 `(channel, order_id)`라 유령의 대조는 공짜.
   그래서 #3이 이미 제일 빠르다(19ms).
3. **#1/#2는 원장도 Seq Scan** — 채널이 전부 naver라 선택도가 없고 기간으로만 거른다. 이건 **남은 관찰거리**.

## 결과 — 인덱스 추가 후 (V3)

`settlement_line(batch_id, channel, order_id)` 추가. settlement_line 접근이 **풀스캔 → 인덱스 스캔**으로
바뀌었다(겨냥 성공). 그런데 **총 시간은 거의 안 줄고, 병목이 `order_ledger` 로 옮겨갔다.**

| 쿼리 | settlement_line 접근 | order_ledger 접근 | 시간 (전→후) | settlement_line 버퍼 (전→후) |
|---|---|---|---|---|
| #1 누락 | Seq → **Index Only Scan** | Seq (그대로) | 52 → 45 ms | 4209 → 95 |
| #2 불일치 | Seq → **Bitmap Index Scan** | Seq (그대로) | 47 → 55 ms | 4209 → 112 |
| #3 유령 | Seq → **Bitmap Index Scan** | Index Only(PK, 그대로) | 19 → 15 ms | 4209 → 112 |

### 배운 것 (여기가 진짜 소득)
1. **인덱스는 겨냥한 걸 해냈다** — settlement_line 읽기가 ~40배↓ (버퍼 4209 → ~100). 배치 하나 뽑으려
   50만 줄을 다 읽던 게, 인덱스로 그 배치의 ~5천 줄만 집는다.
2. **그런데 총 시간은 별로 안 줄었다 — 병목이 `order_ledger` 풀스캔으로 이동**(#1/#2).
   앞 단계에서 "남은 관찰거리"로 찍어둔 게 실측으로 확인됨. **인덱스 하나가 문제를 옮긴다.**
3. **시간이 안 준 이유**: 50만 건이 메모리에 다 올라가 있어(`shared hit` = 캐시 적중), 캐시된 페이지
   풀스캔은 CPU상 싸다 → 인덱스 이득이 **시간보다 버퍼(I/O)에 먼저** 나타난다. 데이터가 더 크거나
   캐시가 콜드면 이 격차가 시간으로도 벌어진다. (그래서 "버퍼가 줄었다"가 "시간이 줄었다"보다 정직한 신호.)

## 결과 — order_ledger 인덱스까지 (V4)

`order_ledger(channel, occurred_at)` 추가. #1/#2 의 마지막 order_ledger 풀스캔이 **범위(bitmap) 스캔**으로
바뀌어, 이제 **양쪽 테이블 다 인덱스로 좁혀진다**. 병목이 사실상 사라졌다.

| 쿼리 | 인덱스 없음 | +V3 (line) | +order_ledger | order_ledger 접근(최종) |
|---|---|---|---|---|
| #1 누락 | 52 ms | 45 ms | **3.5 ms** | Seq → **Bitmap Index Scan** |
| #2 불일치 | 47 ms | 55 ms | **14.6 ms** | Seq → **Bitmap Index Scan** |
| #3 유령 | 19 ms | 15 ms | 49 ms* | Index Only(PK) — **불변** |

`*` #3 은 이 인덱스를 안 쓴다(계획·버퍼 15267 동일). 49ms 는 순수 측정 노이즈 —
같은 계획이 19/15/49ms 로 튄다. → **버퍼가 시간보다 정직한 지표**임을 다시 확인.

### 배운 것
1. **병목이 이동했다.** settlement_line 풀스캔 → (V3) → order_ledger 풀스캔 → (V4) → 소멸.
   인덱스 하나가 문제를 없애는 게 아니라 **다음 병목으로 넘긴다**. 그 다음 병목을 또 측정해서 잡는다.
2. 두 인덱스가 함께 #1/#2 의 **모든 seq scan 을 제거** → #1 **52→3.5ms(~15배)**, #2 **47→15ms(~3배)**.
   이번엔 양쪽 다 ~5,000 줄로 좁혀져 병렬 워커도 사라지고(작아서 불필요) 시간이 실제로 줄었다.
3. **#3 은 처음부터 이 인덱스가 필요 없었다**(예측대로) — 유령의 원장 대조는 PK 로 이미 해결.
   남은 비용은 명세 5,050 줄에 대한 PK 프로브(anti join)로, 이미 인덱스 기반이라 정상 범위.

## 결론
- 스키마에 남길 인덱스: **`V3` settlement_line(batch_id, channel, order_id)** + **`V4` order_ledger(channel, occurred_at)**.
- 둘 다 **추측이 아니라 EXPLAIN 이 가리켜서** 넣었고, 전/후 실측으로 효과를 확인했다.
- 다음(범위 밖): 캐시 콜드/더 큰 규모에서 시간 격차 재확인 · #3 의 PK 프로브를 더 짜낼지.
