# Coupon Issue Performance Tests

This directory contains the k6 and SQL assets for comparing:

- `pessimistic`: `POST /coupons/issue/{couponId}/pessimistic`
- `redis-transactional`: `POST /coupons/issue/{couponId}/redis-transactional`
- `redis-event`: `POST /coupons/issue/{couponId}/redis-event`

## 1. Start the stack

```bash
docker compose up -d --build
```

The compose file pins local resources for the main bottleneck targets:

- app: `1 CPU`, `1GB`
- MySQL: `1 CPU`, `1GB`
- Redis: `0.5 CPU`, `512MB`

## 2. Seed DB and tokens

```bash
docker exec -i coupon-toy-mysql mysql -uroot -p'7815aA!@' CouponToyProject < performance/sql/reset-and-seed.sql
mkdir -p performance/data
node performance/scripts/generate-jwt-tokens.js 100000 > performance/data/tokens.csv
```

The seed script creates three coupons:

- coupon `1`: pessimistic
- coupon `2`: redis-transactional
- coupon `3`: redis-event

Before each Redis strategy run, reset the Redis admission keys:

```bash
docker exec coupon-toy-redis redis-cli DEL \
  coupon:issued:2 coupon:total:2 \
  coupon:issued:3 coupon:total:3
```

## 3. Run k6

Smoke:

```bash
k6 run -e STRATEGY=pessimistic -e COUPON_ID=1 -e SCENARIO=smoke performance/k6/coupon-issue.js
k6 run -e STRATEGY=redis-transactional -e COUPON_ID=2 -e SCENARIO=smoke performance/k6/coupon-issue.js
k6 run -e STRATEGY=redis-event -e COUPON_ID=3 -e SCENARIO=smoke performance/k6/coupon-issue.js
```

Step load:

```bash
k6 run -e STRATEGY=pessimistic -e COUPON_ID=1 -e SCENARIO=step performance/k6/coupon-issue.js
k6 run -e STRATEGY=redis-transactional -e COUPON_ID=2 -e SCENARIO=step performance/k6/coupon-issue.js
k6 run -e STRATEGY=redis-event -e COUPON_ID=3 -e SCENARIO=step performance/k6/coupon-issue.js
```

Other scenarios:

```bash
k6 run -e SCENARIO=spike -e STRATEGY=redis-event -e COUPON_ID=3 performance/k6/coupon-issue.js
k6 run -e SCENARIO=soak -e RATE=700 -e STRATEGY=redis-event -e COUPON_ID=3 performance/k6/coupon-issue.js
k6 run -e SCENARIO=soldout -e STRATEGY=redis-event -e COUPON_ID=3 performance/k6/coupon-issue.js
k6 run -e SCENARIO=duplicate -e STRATEGY=redis-event -e COUPON_ID=3 performance/k6/coupon-issue.js
```

For `redis-event`, wait until `coupon_issue_event_inflight` returns `0` before running consistency checks.

## 4. Check consistency

For Redis strategies, first capture Redis SCARD:

```bash
docker exec coupon-toy-redis redis-cli SCARD coupon:issued:3
```

Then edit `@coupon_id` and `@redis_scard` in `performance/sql/consistency-check.sql`, and run:

```bash
docker exec -i coupon-toy-mysql mysql -uroot -p'7815aA!@' CouponToyProject < performance/sql/consistency-check.sql
```

The result is valid only when:

- `issue_rows <= total_count`
- duplicate query returns no rows
- `issued_count = issue_rows`
- Redis `SCARD = issue_rows` for Redis strategies

## 5. Bottleneck evidence

Use Grafana `Coupon Local Monitoring` and Prometheus with these signals:

```promql
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


## 6. Generate a Markdown report

Save k6 summary JSON files with `--summary-export`:

```bash
mkdir -p performance/results
k6 run --summary-export performance/results/smoke-pessimistic.json -e STRATEGY=pessimistic -e COUPON_ID=1 -e SCENARIO=smoke performance/k6/coupon-issue.js
k6 run --summary-export performance/results/smoke-redis-transactional.json -e STRATEGY=redis-transactional -e COUPON_ID=2 -e SCENARIO=smoke performance/k6/coupon-issue.js
k6 run --summary-export performance/results/smoke-redis-event.json -e STRATEGY=redis-event -e COUPON_ID=3 -e SCENARIO=smoke performance/k6/coupon-issue.js
```

Aggregate them into a readable Markdown report:

```bash
node performance/scripts/aggregate-k6-results.js
```

Default output:

```text
docs/coupon-performance-test/results/latest-summary.md
```
