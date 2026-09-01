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

### 다음 (파생 이슈)
- **금액 불일치** — 명세 줄은 있으나 합이 원장과 다름(부분정산 합산·수수료 검증).
- **유령 정산** — 명세엔 있고 원장엔 없음(반대 방향).
- **T+2 시간창** — 아직 정산 안 된 건을 누락으로 오검출하지 않기.

### 아직 만들지 않음 (YAGNI)
- Kafka / 스트림 처리 — 로직 정답을 세운 뒤의 자리.
- 규칙 엔진 — 금액·수수료 검증에서 본격화.
