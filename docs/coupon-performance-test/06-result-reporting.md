# 06. 결과 리포트 자동 생성

k6 실행 결과를 한눈에 보기 위해 raw summary JSON을 모아 Markdown 리포트를 생성한다.

## 1. k6 결과 저장 방식

k6 실행 시 `--summary-export` 옵션을 붙여 JSON 파일을 저장한다.

Smoke 예시:

```bash
mkdir -p performance/results

k6 run --summary-export performance/results/smoke-pessimistic.json \
  -e STRATEGY=pessimistic \
  -e COUPON_ID=1 \
  -e SCENARIO=smoke \
  performance/k6/coupon-issue.js

k6 run --summary-export performance/results/smoke-redis-transactional.json \
  -e STRATEGY=redis-transactional \
  -e COUPON_ID=2 \
  -e SCENARIO=smoke \
  performance/k6/coupon-issue.js

k6 run --summary-export performance/results/smoke-redis-event.json \
  -e STRATEGY=redis-event \
  -e COUPON_ID=3 \
  -e SCENARIO=smoke \
  performance/k6/coupon-issue.js
```

파일명에는 시나리오와 전략명을 넣는다. 집계 스크립트가 파일명에서 `smoke`, `step`, `pessimistic`, `redis-transactional`, `redis-event`를 읽어 표에 표시한다.

## 2. 리포트 생성

```bash
node performance/scripts/aggregate-k6-results.js \
  --input performance/results \
  --output docs/coupon-performance-test/results/latest-summary.md
```

생성되는 문서:

```text
docs/coupon-performance-test/results/latest-summary.md
```

## 3. 리포트에 포함되는 항목

자동 집계 항목:

- 시나리오
- 전략
- PASS/FAIL 판정
- 요청 수
- req/s
- 성공 수
- `409` 수
- p95/p99/max latency
- check 통과율
- `unexpected_status_rate`
- `server_error_rate`
- `dropped_iterations`
- threshold 실패 상세

수동 기록 항목:

- Redis `SCARD`
- DB `coupon_issues` row 수
- `coupons.issued_count`
- 중복 row 수
- App/MySQL/Redis CPU
- Hikari pending
- Tomcat busy threads
- MySQL row lock waits
- async event inflight/lag
- 병목 판단

k6 summary JSON만으로는 DB/Redis 정합성과 컨테이너 자원 사용률을 확정할 수 없으므로, 해당 항목은 Grafana와 SQL 확인 후 리포트 하단 표에 직접 기록한다.

## 4. 판정 기준

집계 스크립트는 아래 조건 중 하나라도 해당하면 FAIL로 표시한다.

| 조건 | 기준 |
|---|---:|
| 동기 API p95 | `500ms` 초과 |
| 비동기 API p95 | `100ms` 초과 |
| `unexpected_status_rate` | `0.1%` 이상 |
| `server_error_rate` | `0.1%` 이상 |
| `dropped_iterations / iterations` | `1%` 이상 |
| k6 threshold | 실패 존재 |

## 5. Step load 결과 저장 예시

```bash
k6 run --summary-export performance/results/step-pessimistic.json \
  -e STRATEGY=pessimistic \
  -e COUPON_ID=1 \
  -e SCENARIO=step \
  performance/k6/coupon-issue.js

k6 run --summary-export performance/results/step-redis-transactional.json \
  -e STRATEGY=redis-transactional \
  -e COUPON_ID=2 \
  -e SCENARIO=step \
  performance/k6/coupon-issue.js

k6 run --summary-export performance/results/step-redis-event.json \
  -e STRATEGY=redis-event \
  -e COUPON_ID=3 \
  -e SCENARIO=step \
  performance/k6/coupon-issue.js
```

그 후 다시 리포트를 생성한다.

```bash
node performance/scripts/aggregate-k6-results.js
```
