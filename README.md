# settlement-recon

정산 대사(Settlement Reconciliation) — 셀러가 플랫폼에게서 받은 정산 명세가 맞는지 검증한다.
개인 학습 프로젝트. 대용량 처리와 정합성 설계가 목적.

> 계산 = 내가 남에게 얼마 줄까 (도구 많음) · **대사 = 남이 나에게 준 게 맞나 (여기)**

## 설계 원칙
1. 정산 규칙은 코드가 아니라 **데이터**로 둔다 (회사마다 규칙이 다른 게 본질).
2. 채널을 추상화한다. `정산명세 → 표준 이벤트` 어댑터 구조.
3. 핵심 자산은 **"오차를 찾는 로직"**. 로그인·결제·대시보드는 나중.
4. 대용량을 전제로 설계한다 (주문 수십만 × 명세 수십만의 짝짓기).

## 스키마 정책
- **Flyway 가 스키마의 주인** (`src/main/resources/db/migration/V*.sql`).
- 하이버네이트는 `ddl-auto: validate` — 생성/변경 없이 엔티티 ↔ 스키마 일치 검사만.
- 테스트도 Flyway 로 돌려서, 테스트가 곧 운영 DDL 검증이 된다.

## 실행
```
docker compose up -d      # Postgres
./gradlew build           # 테스트는 Testcontainers 로 Postgres 를 띄운다
./gradlew bootRun
```

## 현재 범위 (첫 슬라이스) — ✅ 누락 검출 완료 (#1)
**누락 검출**: 정산 배치 단위로, 원장엔 있는데 명세에 줄이 하나도 없는 주문을 찾는다.
- 원장(진실) `order_ledger(channel, order_id, amount, occurred_at)` — `(channel, order_id)` 자연키
- 명세 `settlement_line(batch_id, channel, order_id, settled_amount)` — 부분정산으로 여러 줄 가능(대리키)
- 배치 기간 `[period_start, period_end)` 반열림으로 원장을 스코프.
- 검출: `OrderLedgerRepository.findMissingByBatchId` — `(channel, order_id)`로 대조해 대응 명세가
  하나도 없으면(`NOT EXISTS`) 누락. 조인은 DB에서 수행(대용량 원칙).
- **원장 ↔ 명세는 FK로 묶지 않는다** — 그래야 "명세엔 있고 원장엔 없는" 유령 정산도 넣고 잡을 수 있다.
- 돈은 `numeric(19,4)`(≠ float), 시각은 `timestamptz`(배치 경계 흔들림 방지).

검증: 원장 N건 중 M건 뺀 명세를 넣고 검출이 정확히 그 M건인지 확인(`MissingDetectionTest`).
함정 행(명세 있음·기간 밖·다른 채널·부분정산 2줄)을 섞어, 쿼리를 조금만 뒤집어도 red가 되게 설계.
데이터는 실제 명세 대신 **합성 데이터**로 시작한다(우리가 정답을 쥐므로 검증이 성립).

### ✅ T+2 유예 완료 (#4)
아직 정산될 때가 안 된 건을 #1 누락이 오검출하지 않게 한다.
- 유예일수는 **배치가 데이터로** 들고 있다 — `settlement_batch.grace_days`(설계원칙 1: 규칙은 데이터로).
  채널·배치마다 유예가 달라도 컬럼 값만 바뀐다. `0` = 유예 없음(#1 과 동일).
- 검출 조건에 한 줄 추가: `occurred_at < period_end - grace_days`(달력일). 마감 정각은 유예로 봄(`<`).
  날짜 산술은 Postgres `make_interval` 로 DB에서(대용량 원칙).
- 검증: 경계(컷오프 직전/정각/이후)를 섞어 `T2SettlementWindowTest`. `<`→`<=` 로 뒤집으면 red.
- 영업일(주말·공휴일) 기준은 범위에서 뺐다 → **영업일 유예**로 분리(아래).

### ✅ 유령 정산 완료 (#3)
명세엔 있는데 원장엔 대응 주문이 없는 줄(유령/오정산)을 잡는다 — #1 누락의 반대 방향.
- 앵커가 명세다: `SettlementLineRepository.findGhostByBatchId` — 이 배치의 명세 줄을
  원장에 `(channel, order_id)`로 대조해 하나도 없으면(`NOT EXISTS`) 유령.
- 원장 쪽은 배치·기간·유예로 자르지 않는다 — 유령은 **순수 키 부재**라 period 를 걸면
  '다른 창에서 정산된 정상 주문'을 오검출한다. 그래서 #1 보다 쿼리가 오히려 짧다.
- 부분정산이면 유령도 **줄 단위로 다** 나온다(dedupe 안 함) — "명세 여럿·원장 1건"이던 #1 의 거울상.
- 성립 근거: 원장 ↔ 명세에 FK 가 없어야 이 케이스를 넣고 잡을 수 있다(V1 주석).
- 검증: 정상 매칭 + 유령(부분정산 2줄·채널만 다른 함정 포함)을 섞어 `GhostSettlementTest`.
  `not exists`→`exists` 로 뒤집으면 이 테스트만 red.

### ✅ 금액 불일치 완료 (#2)
명세는 있는데 `(channel, order_id)`별 `SUM(settled_amount)`이 원장 `amount`와 다른 주문을 잡는다.
#1/#3 이 '존재/부재'였다면 이건 '비교' — 그래서 집계가 들어간다.
- 앵커는 원장: `OrderLedgerRepository.findAmountMismatchByBatchId` — `settlement_line`을
  `(channel, order_id)`로 **JOIN** 후 `GROUP BY … HAVING o.amount <> SUM(...)`.
- JOIN 이 '명세 있는 주문'만 남겨 **명세 없는 주문(=#1)은 자동 제외** — #2 는 #1 과 안 겹친다.
- 유예는 **안 넣는다**(순수 합-비교). '유예 중 부족정산은 진행 중'은 파생 이슈로 분리(아래 #6).
- 결과는 **프로젝션**(`AmountMismatch`: 채널·주문·원장액·명세합) — 어느 테이블 행도 아닌
  계산값이라 엔티티로는 못 담는다. 별칭은 큰따옴표로(Postgres 소문자 접힘 방지).
- Postgres `numeric` 의 `<>` 는 **값 비교**(`1000.00 = 1000.0000`)라 부족·초과를 다 잡고
  `BigDecimal.equals` 의 scale 함정도 안 밟는다.
- 검증: 부족·초과 + 함정(합 일치·scale만 다름·명세 없음·기간 밖)을 섞어 `AmountMismatchTest`.
  `<>`→`=` 로 뒤집으면 이 테스트만 red.

### ✅ 대용량 실행계획·인덱스 완료 (#7)
검출 쿼리 3종을 **원장 50만 · 명세 50.5만 · 배치 100** 짜리 합성 데이터(`scripts/bench-load.sql`)에
돌려 `EXPLAIN` 전/후를 비교했다. 인덱스는 **추측이 아니라 실행계획이 가리켜서** 넣는다.
- 배치를 100개로 쪼갠 게 설계다 — 명세 전부가 한 배치면 그 배치 조회 = 전체 조회라
  seq scan 이 오히려 최적이고, 인덱스가 프루닝할 값어치가 안 생긴다.
- 인덱스 없을 때 세 쿼리 **모두** `settlement_line` 을 Parallel Seq Scan 으로 훑고 `batch_id` 만 남긴다.
  배치 하나 뽑는데 50만 줄을 다 읽어 99% 를 버리고 있었다.
- **V3** `settlement_line(batch_id, channel, order_id)` — 겨냥한 걸 해냈다.
  settlement_line 버퍼 **4209 → 95 (~40배↓)**.
- V3 만으로 안 끝났다. 병목이 `order_ledger` 로 **옮겨간 걸 실행계획에서 확인**하고
  **V4** `order_ledger(channel, occurred_at)` 추가 — #1/#2 의 원장 풀스캔 제거.
- 결과: **#1 누락 52 → 45 → 3.5 ms.** (#3 유령은 인덱스와 무관, 이미 PK Index Only Scan)
- 측정치·채택하지 않은 대안·남은 제약은 [`docs/large-volume.md`](docs/large-volume.md) 에 이어서 기록한다.

### ✅ 수수료 검증 완료 (#8)
명세 합을 원장이 아니라 **기대 순액(원장 − 수수료)** 과 비교한다. #2 의 수수료-인지 버전이고,
바뀌는 건 비교 기준 하나뿐 — 조인·스코프·집계는 #2 와 같다.
- 요율은 **데이터**다: `fee_rule(channel, rate)`(Flyway `V5`). **비율로 저장**(0.033 = 3.3%) —
  퍼센트로 두면 계산식마다 `/100` 이 붙고 한 군데만 빠뜨려도 100배 오차가 조용히 난다.
  `numeric(9,6)` 은 0.028750 같은 협의 요율까지 수용(4자리면 잘려 전 건이 오차).
- 검출: `OrderLedgerRepository.findFeeAwareMismatchByBatchId` —
  `left join fee_rule` + `coalesce(rate, 0)`. **inner 로 하면 요율 데이터가 없는 채널이
  '검출 0건'으로 보여 오차가 조용히 묻힌다** — 대사에서 가장 위험한 실패라 검출이 꺼지지 않는 쪽을 택했다.
  규칙 없는 채널은 수수료 0 이 되어 결과가 #2 와 같아진다.
- 기대 순액 = `amount − trunc(amount × rate)`. **버림으로 고정** — 우리가 '공정한 값'을 정하는 게
  아니라 **플랫폼 계산을 재현**하는 것이라서, 반올림 방식이 1원만 어긋나도 정상 건이 전부 불일치가 된다.
  (`floor` 가 아니라 `trunc` 인 이유는 음수(환불)에서 갈리기 때문 — 음수는 지금 범위 밖.)
- `f.rate` 는 `group by` 에 넣어야 한다 — having 에서 참조하는데 집계가 아니고,
  `f.channel` 이 PK 라도 Postgres 는 조인 등식을 통한 함수 종속까지 추적하지 않는다.
  PK 조인이라 행이 불어나지 않아 `SUM` 은 부풀지 않는다.
- 결과 프로젝션에 `appliedRate`·`expectedNet` 을 같이 낸다 — 어긋난 원인이
  '플랫폼이 덜 줘서'인지 '요율 데이터가 틀려서'인지는 적용된 요율을 봐야 갈린다.
- **#8 이 #2 를 포함한다.** 요율 있는 채널에서 #2 는 정상 정산을 전부 거짓 경보로 낸다
  (원장 10,000 vs 명세 9,670). #2 는 함정 케이스를 지키는 기준선이라 코드·테스트로 남기되
  **운영 검출에는 쓰지 않는다**(메서드 주석에 경고).
- 검증: 순액 일치(경계)·반올림 경계·채널별 요율·규칙 없는 채널을 섞어 `FeeValidationTest`.
  `trunc`→`round` 로 뒤집으면 `truncatesFeeInsteadOfRounding` 만, `left join`→`join` 이면
  `stillDetectsOnChannelsWithoutFeeRule` 만 red. **반올림 경계값은 10,050·10,020 을 써야 한다** —
  10,000(330.0)·10,005(330.165)는 trunc 와 round 가 같아서 변이를 못 잡는다.

### 다음 (파생 이슈)
- **영업일 유예** — T+2를 달력일에서 영업일(주말·공휴일 제외) 기준으로.
- **유예-인지 부족정산** — #2 부족 케이스에서, 아직 유예 중이라 나머지가 올 예정인 부분정산을 오검출 않기(초과는 항상 검출).
- **반올림 정책의 데이터화** — 채널별 반올림 방식(버림/반올림)을 `fee_rule` 로. 지금은 버림 고정.
- **정액·혼합 수수료 / 시점별 요율** — `fee_rule` 에 type·effective date. 지금은 채널당 정률 한 행.

### 아직 만들지 않음 (YAGNI)
- Kafka / 스트림 처리 — 로직 정답을 세운 뒤의 자리.
- 규칙 엔진 — 금액·수수료 검증에서 본격화.
