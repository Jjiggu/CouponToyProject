# Coupon Issue Performance Tests

`k6/coupon-issue.js` 는 실험 API(`/lab`, `local` 프로필)를 호출한다. 실행 한 번마다 setup 에서 회원과 쿠폰을 새로 만들고, teardown 에서 `GET /lab/coupons/{id}/result` 와 접수/발급 완료 처리량을 출력한다. 단계별 실험 조건은 [docs/learning](../docs/learning/README.md) 을 따른다.

## 1. 앱 실행

```bash
docker compose up -d mysql redis

# 기존 DB 와 Redis db 0 을 건드리지 않도록 별도 스키마와 Redis db 를 쓴다.
SERVER_PORT=18080 \
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/CouponToyProject_lab?createDatabaseIfNotExist=true' \
SPRING_DATA_REDIS_DATABASE=2 SPRING_JPA_SHOW_SQL=false SPRING_JPA_PROPERTIES_HIBERNATE_SHOW_SQL=false \
./gradlew bootRun
```

`docker-compose.yml` 의 app 컨테이너는 `SPRING_PROFILES_ACTIVE=docker` 라서 실험 API 가 등록되지 않는다.

## 2. k6 실행

| 변수 | 기본값 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | |
| `METHOD` | `SERIALIZABLE` | `READ_COMMITTED`, `REPEATABLE_READ`, `SERIALIZABLE`, `PESSIMISTIC`, `OPTIMISTIC`, `OPTIMISTIC_RETRY`, `REDIS_SET_ONLY`, `REDIS_MULTI_EXEC`, `REDIS_WATCH`, `REDIS_LUA`, `REDIS_ASYNC` |
| `SCENARIO` | `smoke` | `burst`, `duplicate`, `smoke`, `soldout`, `step`, `spike`, `soak` |
| `TOTAL_COUNT` | `100` | 쿠폰 수량 |
| `MEMBERS` | `1000` | setup 에서 만들 회원 수 |
| `ITERATIONS`, `VUS` | 시나리오별 | `burst`, `duplicate` 의 총 요청 수와 동시 VU |
| `DUPLICATE_USERS` | `100` | `duplicate` 에서 요청을 나눠 쓸 회원 수 |
| `WARMUP` | `0` | 별도 쿠폰에 같은 방식으로 먼저 보낼 요청 수 |
| `DUPLICATE_CHECK` | `true` | `false` 면 중복 확인 SELECT 를 건너뛴다. `READ_COMMITTED`, `REPEATABLE_READ`, `SERIALIZABLE` 에서만 |

```bash
BASE_URL=http://localhost:18080 METHOD=SERIALIZABLE SCENARIO=burst \
TOTAL_COUNT=100 MEMBERS=1000 ITERATIONS=1000 VUS=100 WARMUP=200 \
k6 run --summary-export performance/results/stage1-burst-serializable.json performance/k6/coupon-issue.js
```

- 상태 코드: 성공 201, 비동기 접수 202, 거절(품절·중복) 409, 트랜잭션 실패(데드락·락 대기 초과·버전 충돌)·비동기 큐 포화 503
- `OPTIMISTIC_RETRY` 의 재시도 상한과 지연은 앱 쪽 설정이다. bootRun 앞에 `COUPON_ISSUE_OPTIMISTIC_MAX_ATTEMPTS`(기본 5), `COUPON_ISSUE_OPTIMISTIC_BACKOFF_BASE_MS`(10), `COUPON_ISSUE_OPTIMISTIC_BACKOFF_MAX_MS`(100) 를 붙인다
- `REDIS_WATCH` 의 재시도 상한은 `COUPON_ISSUE_REDIS_WATCH_MAX_ATTEMPTS`(기본 10). 커넥션 풀이 없으면 `REDIS_MULTI_EXEC`·`REDIS_WATCH` 는 요청(시도)마다 새 Redis 연결을 맺는다. 이어서 실행할 때는 호스트의 TIME_WAIT 가 빠질 때까지 기다린다(`netstat -an -p tcp | grep -c TIME_WAIT`, 3단계 문서 4-8)
- `coupon_issue_http_duration` 은 발급 요청만의 응답 시간이다. `http_req_duration` 에는 setup/teardown 요청이 섞인다.
- `REDIS_ASYNC` 는 teardown 에서 결과 API 의 `pending`(접수됐지만 확정 결과가 없는 요청)이 0 이 될 때까지 50ms 간격으로 다시 조회한다(최대 120초). 발급 완료 처리량은 그 시점까지로 계산한다
- `REDIS_ASYNC` 의 스레드 풀은 bootRun 앞에 `COUPON_ISSUE_ASYNC_POOL_SIZE`(기본 4), `COUPON_ISSUE_ASYNC_QUEUE_CAPACITY`(50000) 를 붙여 바꾼다

## 3. 정합성 교차 확인

결과 API 의 `checks` 를 원시 SQL 로 다시 확인하려면 `sql/consistency-check.sql` 의 `@coupon_id`, `@redis_scard` 를 바꿔 실행한다.

```bash
docker exec coupon-toy-redis redis-cli -n 2 SCARD coupon:issued:{couponId}
docker exec -i coupon-toy-mysql mysql -uroot -p'7815aA!@' CouponToyProject_lab < performance/sql/consistency-check.sql
```

## 4. 병목 지표

Grafana `Coupon Local Monitoring` 과 Prometheus 에서 아래 신호를 본다. 앱 지표(`coupon_issue_*`)는 4단계에서 지웠다. 방식별 결과·지연은 결과 API 를 본다.

```promql
hikaricp_connections_pending{application="coupon-toy-project"}
tomcat_threads_busy_threads{application="coupon-toy-project"}
mysql_global_status_innodb_row_lock_waits
rate(redis_commands_processed_total[1m])
```

## 5. 이전 결과 리포트

`scripts/aggregate-k6-results.js` 는 이전 구조(JWT, `pessimistic`/`redis-transactional`/`redis-event` 파일명)의 결과를 집계해 `docs/coupon-performance-test/results/latest-summary.md` 를 **덮어쓴다**. 새 실험 결과는 `docs/learning/results/` 에 실행 조건과 함께 남긴다.
