# 쿠폰 발급 성능 테스트 결과 요약

생성 시각: 2026-05-03T09:14:07.214Z

원본 k6 summary JSON 경로: `performance/results`

## 1. 전체 판정

| 전체 파일 수 | PASS | FAIL |
|---:|---:|---:|
| 5 | 3 | 2 |

## 2. 전략별 요약

| 시나리오 | 전략 | 판정 | 요청 수 | req/s | 성공 | 409 | p95(ms) | p99(ms) | max(ms) | checks | unexpected | server error | dropped |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| smoke | pessimistic | PASS | 601 | 10.01 | 601 | 0 | 25.98 | - | 195.89 | 100.000% | 0.000% | 0.000% | 0 |
| smoke | redis-event | PASS | 600 | 10.01 | 600 | 0 | 14.47 | - | 34.81 | 100.000% | 0.000% | 0.000% | 0 |
| smoke | redis-transactional | PASS | 601 | 10.01 | 601 | 0 | 23.27 | - | 41.74 | 100.000% | 0.000% | 0.000% | 0 |
| step | pessimistic | FAIL (p95>500ms, dropped_iterations>=1%) | 463373 | 364.60 | 10000 | 453373 | 14930.40 | 17983.09 | 23410.70 | 100.000% | 0.000% | 0.000% | 481581 |
| step | redis-transactional | FAIL (p95>500ms, dropped_iterations>=1%) | 315124 | 247.08 | 10000 | 305124 | 22506.98 | 36983.72 | 49756.33 | 100.000% | 0.000% | 0.000% | 629860 |

## 3. 판정 실패 상세

| 시나리오 | 전략 | 실패 기준 |
|---|---|---|
| step | pessimistic | p95>500ms<br>dropped_iterations>=1% |
| step | redis-transactional | p95>500ms<br>dropped_iterations>=1% |

## 4. 정합성 확인 기록

아래 항목은 k6 summary JSON만으로는 판단할 수 없으므로 테스트 후 직접 기록한다.

| 전략 | couponId | Redis SCARD | DB issue rows | coupons.issued_count | 중복 row | 정합성 판정 |
|---|---:|---:|---:|---:|---:|---|
| pessimistic | 1 | 0 | 10000 | 10000 | 0 | PASS |
| redis-transactional | 2 | 10000 | 10000 | 10000 | 0 | PASS |
| redis-event | 3 |  |  |  |  |  |

확인 SQL과 Redis 명령은 `docs/coupon-performance-test/04-consistency-check.md`를 따른다.

## 5. 병목 관찰 기록

Grafana `Coupon Local Monitoring` 대시보드에서 확인한 값을 기록한다.

| 전략 | 최대 안정 RPS | API p95 | DB p95 | Redis p95 | App CPU | MySQL CPU | Redis CPU | Hikari pending | Tomcat busy | Row lock waits | Event inflight/lag | 병목 판단 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|
| pessimistic | 364.60 avg completed req/s | 14.93s | - | - | ~99.9% of 1 CPU | ~68.3% of 1 CPU | ~2.6% of 0.5 CPU | 189 | 200 | +322k | - | DB row lock + app thread/connection 대기 |
| redis-transactional |  |  |  |  |  |  |  |  |  |  | - |  |
| redis-event |  |  |  |  |  |  |  |  |  |  |  |  |

## 6. 원본 파일

- `performance/results/smoke-pessimistic.json`
- `performance/results/smoke-redis-event.json`
- `performance/results/smoke-redis-transactional.json`
- `performance/results/step-pessimistic.json`
- `performance/results/step-redis-transactional.json`
