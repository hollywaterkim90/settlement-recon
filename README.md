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

## 현재 범위 (첫 슬라이스)
**누락 검출**: 주문 원장엔 있는데 정산 명세에 안 들어온 건을 찾는다.
- 원장(진실) `OrderLedger{orderId, amount}` ⟕ 명세 `SettlementLine{orderId, settledAmount}`
- `orderId` left join 후 명세 쪽 NULL = 누락.
- 검증: 원장 N건 중 M건을 뺀 명세를 넣고, 검출 결과가 정확히 그 M건인지(변이 테스트).

데이터는 실제 명세 대신 **합성 데이터**로 시작한다(우리가 정답을 쥐므로 검증이 성립).

### 아직 만들지 않음 (YAGNI)
- Kafka / 스트림 처리 — 로직 정답을 세운 뒤의 자리.
- 규칙 엔진 — 금액·수수료 검증(파생 이슈)에서 본격화.
