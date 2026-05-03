# 03. 지표와 병목 분석

## 핵심 지표

Spring/Micrometer:

```promql
sum(rate(coupon_issue_requests_total[1m])) by (strategy, result)
histogram_quantile(0.95, sum(rate(coupon_issue_duration_seconds_bucket[1m])) by (le, strategy))
histogram_quantile(0.95, sum(rate(coupon_issue_db_duration_seconds_bucket[1m])) by (le, strategy))
histogram_quantile(0.95, sum(rate(coupon_issue_redis_duration_seconds_bucket[1m])) by (le, strategy))
coupon_issue_event_inflight
histogram_quantile(0.95, sum(rate(coupon_issue_consistency_lag_seconds_bucket[1m])) by (le))
```

App/JVM/Tomcat/Hikari:

```promql
process_cpu_usage{application="coupon-toy-project"}
system_cpu_usage{application="coupon-toy-project"}
jvm_memory_used_bytes{application="coupon-toy-project"}
tomcat_threads_busy_threads{application="coupon-toy-project"}
hikaricp_connections_active{application="coupon-toy-project"}
hikaricp_connections_pending{application="coupon-toy-project"}
```

MySQL/Redis/컨테이너:

```promql
mysql_global_status_threads_connected
mysql_global_status_innodb_row_lock_waits
rate(mysql_global_status_questions[1m])
rate(redis_commands_processed_total[1m])
redis_connected_clients
redis_memory_used_bytes
sum(rate(container_cpu_usage_seconds_total{name=~"coupon-toy-.*"}[1m])) by (name)
container_memory_usage_bytes{name=~"coupon-toy-.*"}
```

## 전략별 병목 판정

### 비관적 락

병목 판정:

- `mysql_global_status_innodb_row_lock_waits` 증가
- `hikaricp_connections_pending` 증가
- `tomcat_threads_busy_threads`가 max에 근접
- DB CPU 상승
- `coupon_issue_db_duration_seconds` p95 증가

결론 예시:

> `800 RPS` 이후 MySQL row lock wait와 Hikari pending이 함께 증가했고, p95가 `500ms`를 초과했다. 병목은 단일 coupon row에 대한 비관적 락 경합이다.

### Redis Transactional

병목 판정:

- Redis latency보다 DB 저장 latency가 먼저 증가
- `coupon_issue_redis_duration_seconds`는 안정적이지만 `coupon_issue_db_duration_seconds`가 증가
- `hikaricp_connections_pending` 증가
- DB CPU 또는 write throughput 한계 확인

결론 예시:

> Redis 입장 제어는 안정적이지만 `1000 RPS` 이후 DB insert/update p95와 Hikari pending이 증가했다. 병목은 Redis가 아니라 Write DB 저장 구간이다.

### Redis + Spring Event

병목 판정:

- API p95는 낮지만 `coupon_issue_event_inflight`가 지속 증가
- `coupon_issue_consistency_lag_seconds` p95가 `5s` 초과
- `coupon_issue_event_published_total` 증가율이 `completed_total` 증가율보다 큼
- DB CPU/write latency가 증가하거나 이벤트 executor 처리량이 부족

결론 예시:

> API 접수 p95는 `100ms` 이하로 유지됐지만, `1500 RPS` 이후 inflight 이벤트와 consistency lag가 증가했다. 병목은 API가 아니라 비동기 DB writer 처리량이다.

## 결과 리포트 템플릿

| 항목 | 비관적 락 | Redis Transactional | Redis + Event |
|---|---:|---:|---:|
| 최대 안정 RPS |  |  |  |
| p50 |  |  |  |
| p95 |  |  |  |
| p99 |  |  |  |
| 5xx + timeout |  |  |  |
| 중복/품절 응답 |  |  |  |
| App CPU |  |  |  |
| MySQL CPU |  |  |  |
| Redis CPU |  |  |  |
| Hikari pending |  |  |  |
| MySQL row lock wait |  |  |  |
| Event inflight | N/A | N/A |  |
| Event lag p95 | N/A | N/A |  |
| 정합성 |  |  |  |
| 병목 원인 |  |  |  |
