# 05. 테스트 결과 확인 가이드

이 문서는 k6 실행 후 결과를 어디서 보고, 어떤 기준으로 정상/실패를 판단할지 정리한다.

## 1. 확인 순서

테스트가 끝나면 아래 순서로 확인한다.

1. k6 터미널 결과
2. Grafana 대시보드
3. Prometheus 직접 쿼리
4. DB/Redis 정합성
5. Spring app 로그

Smoke 테스트는 병목을 찾는 단계가 아니라, 테스트 환경과 계측이 정상인지 확인하는 단계다.

## 2. k6 터미널 결과

k6 실행이 끝나면 터미널 출력에서 아래 항목을 우선 확인한다.

| 항목 | 의미 | Smoke 정상 기준 |
|---|---|---|
| `checks` | k6 check 통과율 | 거의 `100%` |
| `http_req_duration` | API latency | 동기 `p95 < 500ms`, 비동기 `p95 < 100ms` |
| `http_req_failed` | HTTP 실패율 | 낮아야 함 |
| `dropped_iterations` | k6가 목표 RPS를 못 맞춘 횟수 | `0` 또는 매우 낮음 |
| `unexpected_status_rate` | 예상하지 않은 HTTP status 비율 | threshold 통과 |
| `server_error_rate` | `5xx` 또는 timeout 비율 | threshold 통과 |
| `coupon_issue_success_count` | 성공 응답 수 | Smoke 요청량에 맞게 증가 |
| `coupon_issue_conflict_count` | `409` 응답 수 | 품절/중복이 아니면 낮아야 함 |

전략별 정상 성공 status는 다음과 같다.

| 전략 | 정상 성공 | 정상 실패 |
|---|---:|---:|
| `pessimistic` | `201` | `409` |
| `redis-transactional` | `201` | `409` |
| `redis-event` | `202` | `409` |

`unexpected_status_rate`가 threshold를 넘으면 `201/202/409`가 아닌 응답이 나온 것이다. 대표적으로 `401`, `403`, `404`, `500`이 있다.

## 3. Grafana 확인

Grafana 접속:

```text
http://localhost:3000
```

기본 계정:

```text
admin / admin
```

대시보드:

```text
Coupon Local Monitoring
```

Smoke 테스트 후 Grafana에서 확인할 항목은 다음과 같다.

| 패널/지표 | 확인 내용 |
|---|---|
| API Throughput | 요청이 실제로 들어왔는지 확인 |
| API Latency p95 | 전략별 latency가 찍히는지 확인 |
| Coupon Issue Requests | `strategy`, `result` label이 증가하는지 확인 |
| Coupon DB Duration p95 | DB 구간 지표가 찍히는지 확인 |
| Coupon Redis Duration p95 | Redis 전략에서 Redis 구간 지표가 찍히는지 확인 |
| Event Inflight | `redis-event` 후 다시 `0`으로 내려오는지 확인 |
| Event Completion Lag | 비동기 DB 반영 지연시간 확인 |
| App CPU/Memory | Spring app 컨테이너 자원 사용률 확인 |
| MySQL CPU/Memory | DB 자원 사용률 확인 |
| Redis CPU/Memory | Redis 자원 사용률 확인 |
| Hikari Pending | DB 커넥션 대기 발생 여부 확인 |
| Tomcat Busy Threads | 요청 처리 thread 포화 여부 확인 |
| MySQL Row Lock Waits | row lock wait 증가 여부 확인 |
| Redis Ops/sec | Redis 명령 처리량 확인 |

Smoke에서 중요한 것은 수치 자체보다 **모든 지표가 정상적으로 수집되는지**다.

## 4. Prometheus 직접 확인

Grafana 패널이 이상하면 Prometheus에서 직접 쿼리한다.

Prometheus:

```text
http://localhost:9090
```

자주 쓰는 쿼리:

```promql
sum(rate(coupon_issue_requests_total[1m])) by (strategy, result)
histogram_quantile(0.95, sum(rate(coupon_issue_duration_seconds_bucket[1m])) by (le, strategy))
histogram_quantile(0.95, sum(rate(coupon_issue_db_duration_seconds_bucket[1m])) by (le, strategy))
histogram_quantile(0.95, sum(rate(coupon_issue_redis_duration_seconds_bucket[1m])) by (le, strategy))
coupon_issue_event_inflight
histogram_quantile(0.95, sum(rate(coupon_issue_consistency_lag_seconds_bucket[1m])) by (le))
hikaricp_connections_pending{application="coupon-toy-project"}
tomcat_threads_busy_threads{application="coupon-toy-project"}
mysql_global_status_innodb_row_lock_waits
rate(redis_commands_processed_total[1m])
```

## 5. DB/Redis 정합성 확인

Smoke 후에도 정합성은 확인한다.

Redis SCARD:

```bash
docker exec coupon-toy-redis redis-cli SCARD coupon:issued:1
docker exec coupon-toy-redis redis-cli SCARD coupon:issued:2
docker exec coupon-toy-redis redis-cli SCARD coupon:issued:3
```

MySQL 접속:

```bash
docker exec -it coupon-toy-mysql mysql -uroot -p'7815aA!@' CouponToyProject
```

쿠폰별 발급 수량:

```sql
SELECT coupon_id, total_count, issued_count
FROM coupons
ORDER BY coupon_id;
```

발급 row 수:

```sql
SELECT coupon_id, COUNT(*) AS issue_count
FROM coupon_issues
GROUP BY coupon_id
ORDER BY coupon_id;
```

중복 발급 확인:

```sql
SELECT coupon_id, member_id, COUNT(*) AS duplicate_count
FROM coupon_issues
GROUP BY coupon_id, member_id
HAVING COUNT(*) > 1;
```

정상 기준:

- `issue_count <= total_count`
- 중복 발급 조회 결과가 없어야 함
- `issued_count = issue_count`
- Redis 전략은 `SCARD = issue_count`
- `redis-event`는 `coupon_issue_event_inflight = 0` 이후 확인

## 6. Spring 로그 확인

예상하지 않은 status가 나오면 Spring app 로그를 먼저 확인한다.

```bash
docker logs --tail 200 coupon-toy-app
```

실시간으로 보려면:

```bash
docker logs -f coupon-toy-app
```

## 7. 흔한 실패 원인

### `unexpected_status_rate` threshold 실패

의미:

```text
201/202/409가 아닌 status가 발생했다.
```

확인 순서:

1. k6 터미널의 check 실패 여부 확인
2. `docker logs --tail 200 coupon-toy-app` 확인
3. JWT 서명 오류, endpoint 오타, 서버 예외 여부 확인

대표 원인:

| 실제 원인 | 자주 나오는 status | 확인 방법 |
|---|---:|---|
| JWT secret 불일치 | `401` 또는 인증 실패 | Spring 로그에 `SignatureVerificationException` |
| token CSV 경로 오류 | k6 init 실패 | `no such file or directory` |
| 전략명 오타 | `404` | 요청 URL 확인 |
| 서버 예외 | `500` | Spring 로그 stacktrace 확인 |
| 품절/중복 | `409` | 정상 status라 unexpected 아님 |

JWT secret이 불일치하면 token을 다시 생성한다.

```bash
JWT_SECRET='p5Xy7UvZ9wR3tqK+LmNf6gTjWbDcE2AhFkIoM1JQl4o=' \
node performance/scripts/generate-jwt-tokens.js 100000 > performance/data/tokens.csv
```

### token CSV 경로 오류

k6의 `open()`은 스크립트 파일 위치인 `performance/k6/` 기준으로 상대경로를 해석한다.

기본값이 이미 `../data/tokens.csv`이므로 일반적으로 `TOKENS_FILE`은 생략한다.

```bash
k6 run \
  -e STRATEGY=pessimistic \
  -e COUPON_ID=1 \
  -e SCENARIO=smoke \
  performance/k6/coupon-issue.js
```

직접 지정하려면 다음처럼 쓴다.

```bash
k6 run \
  -e STRATEGY=pessimistic \
  -e COUPON_ID=1 \
  -e SCENARIO=smoke \
  -e TOKENS_FILE=../data/tokens.csv \
  performance/k6/coupon-issue.js
```

또는 절대경로를 사용한다.

```bash
k6 run \
  -e STRATEGY=pessimistic \
  -e COUPON_ID=1 \
  -e SCENARIO=smoke \
  -e TOKENS_FILE="/performance/data/tokens.csv" \
  performance/k6/coupon-issue.js
```

쉘에서 줄바꿈을 사용할 때 `\` 뒤에는 공백을 두면 안 된다.

## 8. Smoke 통과 후 다음 단계

Smoke가 정상이라면 바로 모든 전략의 Step load를 한 번에 돌리지 않는다.

권장 순서:

1. `pessimistic` Step load 실행
2. Grafana에서 DB row lock, Hikari pending, DB CPU 확인
3. 병목 시점의 RPS와 p95 기록
4. `redis-transactional` Step load 실행
5. 같은 지표 기준으로 비교
6. `redis-event` Step load 실행
7. event inflight, completion lag, DB writer 병목 확인

Step load 명령:

```bash
k6 run -e STRATEGY=pessimistic -e COUPON_ID=1 -e SCENARIO=step performance/k6/coupon-issue.js
k6 run -e STRATEGY=redis-transactional -e COUPON_ID=2 -e SCENARIO=step performance/k6/coupon-issue.js
k6 run -e STRATEGY=redis-event -e COUPON_ID=3 -e SCENARIO=step performance/k6/coupon-issue.js
```
