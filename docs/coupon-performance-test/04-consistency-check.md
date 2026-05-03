# 04. 정합성 검증

## 검증 기준

쿠폰 발급 테스트는 성능이 좋아도 정합성이 깨지면 실패다.

정합성 통과 기준:

- `coupon_issues` row 수가 `coupons.total_count`를 초과하지 않는다.
- 같은 `coupon_id`, `member_id` 조합이 2개 이상 존재하지 않는다.
- `coupons.issued_count = coupon_issues count`
- Redis 전략에서는 Redis `SCARD`와 DB 발급 row 수가 일치한다.
- `redis-event`는 `coupon_issue_event_inflight = 0` 확인 후 검증한다.

## DB 정합성 검증

`performance/sql/consistency-check.sql`에서 검증 대상 쿠폰을 지정한다.

```sql
SET @coupon_id = 1;
SET @redis_scard = 0;
```

전략별 couponId:

| couponId | 전략 |
|---:|---|
| `1` | 비관적 락 |
| `2` | Redis Transactional |
| `3` | Redis + Spring Event |

실행:

```bash
docker exec -i coupon-toy-mysql mysql -uroot -p'7815aA!@' CouponToyProject < performance/sql/consistency-check.sql
```

## Redis SCARD 확인

Redis 전략은 Redis 입장 제어 결과도 함께 확인한다.

Redis Transactional:

```bash
docker exec coupon-toy-redis redis-cli SCARD coupon:issued:2
```

Redis + Spring Event:

```bash
docker exec coupon-toy-redis redis-cli SCARD coupon:issued:3
```

확인한 값을 `performance/sql/consistency-check.sql`의 `@redis_scard`에 넣고 SQL을 다시 실행한다.

## 비동기 drain 확인

`redis-event`는 API 응답과 DB 저장 시점이 다르다. 아래 값이 `0`이 된 뒤 정합성을 검증한다.

```promql
coupon_issue_event_inflight
```

또는 Prometheus HTTP API로 확인한다.

```bash
curl 'http://localhost:9090/api/v1/query?query=coupon_issue_event_inflight'
```

## 실패 판정 예시

과발급 실패:

```text
issue_rows > total_count
```

중복 발급 실패:

```text
duplicate query returns rows
```

Redis/DB 불일치:

```text
redis_scard != issue_rows
```

비동기 미완료 상태에서 검증:

```text
coupon_issue_event_inflight > 0
```
