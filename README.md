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

### 다음 (파생 이슈)
- **영업일 유예** — T+2를 달력일에서 영업일(주말·공휴일 제외) 기준으로.
- **유예-인지 부족정산** — #2 부족 케이스에서, 아직 유예 중이라 나머지가 올 예정인 부분정산을 오검출 않기(초과는 항상 검출).
- **대용량 첫 맛** — 합성 데이터 수십만 건 + `EXPLAIN`으로 실행계획 확인, `settlement_line(batch_id, channel, order_id)` 인덱스로 `NOT EXISTS` 살리기.

### 아직 만들지 않음 (YAGNI)
- Kafka / 스트림 처리 — 로직 정답을 세운 뒤의 자리.
- 규칙 엔진 — 금액·수수료 검증에서 본격화.
