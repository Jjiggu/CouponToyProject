# 02. 실행 절차

## 1. 기존 컨테이너 확인

기존 `coupon-toy-*` 컨테이너가 `8080`, `3306`, `6379`, `9090`, `3000` 포트를 사용 중이면 먼저 중지한다.

```bash
docker ps -a --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
```

이전 compose 프로젝트가 구형 `src/main/resources/docker-compose.yml`로 실행됐고 해당 파일이 로컬에 남아 있는 경우:

```bash
if [ -f src/main/resources/docker-compose.yml ]; then
  docker compose -p resources -f src/main/resources/docker-compose.yml down
fi
```

DB 데이터가 중요하면 `down` 전에 dump를 떠야 한다. `down -v`는 볼륨 삭제가 필요할 때만 사용한다.

## 2. 테스트 스택 실행

```bash
docker compose up -d --build
```

접속 주소:

| 도구 | URL |
|---|---|
| Spring Actuator | http://localhost:8080/actuator/prometheus |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |
| cAdvisor | http://localhost:8081 |

Grafana 기본 계정은 `admin` / `admin`이다.

## 3. DB 초기화와 테스트 데이터 생성

```bash
docker exec -i coupon-toy-mysql mysql -uroot -p'7815aA!@' CouponToyProject < performance/sql/reset-and-seed.sql
```

생성되는 쿠폰:

| couponId | 전략 |
|---:|---|
| `1` | 비관적 락 |
| `2` | Redis Transactional |
| `3` | Redis + Spring Event |

## 4. JWT token CSV 생성

```bash
mkdir -p performance/data
JWT_SECRET='p5Xy7UvZ9wR3tqK+LmNf6gTjWbDcE2AhFkIoM1JQl4o=' \
node performance/scripts/generate-jwt-tokens.js 100000 > performance/data/tokens.csv
```

`JWT_SECRET`은 Docker Spring 서버의 `JWT_SECRET_KEY`와 같아야 한다. 다르면 k6 요청이 인증 실패하고 `unexpected_status_rate`가 증가한다.

`performance/data/`는 git 추적에서 제외된다.

## 5. Redis 키 초기화

Redis 전략 실행 전에는 admission key를 초기화한다.

```bash
docker exec coupon-toy-redis redis-cli DEL \
  coupon:issued:2 coupon:total:2 \
  coupon:issued:3 coupon:total:3
```

## 6. Smoke 테스트

```bash
k6 run -e STRATEGY=pessimistic -e COUPON_ID=1 -e SCENARIO=smoke performance/k6/coupon-issue.js
k6 run -e STRATEGY=redis-transactional -e COUPON_ID=2 -e SCENARIO=smoke performance/k6/coupon-issue.js
k6 run -e STRATEGY=redis-event -e COUPON_ID=3 -e SCENARIO=smoke performance/k6/coupon-issue.js
```

## 7. Step load 테스트

```bash
k6 run -e STRATEGY=pessimistic -e COUPON_ID=1 -e SCENARIO=step performance/k6/coupon-issue.js
k6 run -e STRATEGY=redis-transactional -e COUPON_ID=2 -e SCENARIO=step performance/k6/coupon-issue.js
k6 run -e STRATEGY=redis-event -e COUPON_ID=3 -e SCENARIO=step performance/k6/coupon-issue.js
```

## 8. 추가 시나리오

```bash
k6 run -e SCENARIO=spike -e STRATEGY=redis-event -e COUPON_ID=3 performance/k6/coupon-issue.js
k6 run -e SCENARIO=soak -e RATE=700 -e STRATEGY=redis-event -e COUPON_ID=3 performance/k6/coupon-issue.js
k6 run -e SCENARIO=soldout -e STRATEGY=redis-event -e COUPON_ID=3 performance/k6/coupon-issue.js
k6 run -e SCENARIO=duplicate -e STRATEGY=redis-event -e COUPON_ID=3 performance/k6/coupon-issue.js
```

`redis-event`는 API 응답이 `202 Accepted`여도 DB 저장이 비동기로 진행된다. 정합성 검증 전 `coupon_issue_event_inflight`가 `0`인지 확인한다.


## 9. 결과 확인

Smoke 테스트 후 결과 확인 방법은 [05. 테스트 결과 확인 가이드](05-result-check-guide.md)를 따른다.
