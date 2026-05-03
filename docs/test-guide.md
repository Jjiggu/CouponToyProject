# 테스트 가이드

`k6` 기반 쿠폰 발급 비교 테스트를 직접 실행할 때 보는 문서다. 이 가이드 기준으로 실행하면 Redis 기반 발급과 비관적 락 전용 발급을 같은 조건에서 비교할 수 있다.

## 목적

- Redis 기반 발급: `POST /coupons/issue/{couponId}`
- 비교용 비관적 락 전용 발급: `POST /coupons/issue/pessimistic/{couponId}`
- 측정 구간에는 쿠폰 발급 요청만 포함
- 회원가입, 로그인, 쿠폰 생성은 `k6 setup()` 에서 자동 수행

## 사전 준비

- Docker daemon 이 실행 중이어야 한다.
- `k6` 가 설치돼 있어야 한다.
- 아래 포트를 다른 프로세스가 사용하지 않아야 한다.
  - MySQL: `3306`
  - Redis: `6379`
  - Spring Boot: `8080`

필수 명령 확인:

```bash
docker --version
docker compose version
k6 version
```

## 실행 방법

가장 단순한 방법은 MySQL, Redis, Spring Boot 를 Compose 로 한 번에 띄우는 방식이다.

```bash
docker compose up --build -d
```

Grafana/Prometheus 모니터링까지 같이 보려면 아래처럼 실행한다.

```bash
docker compose up --build -d
```

기동 확인:

```bash
docker compose ps
```

정상이라면 아래 3개 컨테이너가 떠 있어야 한다.

- `coupon-toy-mysql`
- `coupon-toy-redis`
- `coupon-toy-app`

모니터링까지 함께 올렸다면 추가로 아래도 떠 있어야 한다.

- `coupon-toy-prometheus`
- `coupon-toy-grafana`
- `coupon-toy-cadvisor`
- `coupon-toy-mysqld-exporter`
- `coupon-toy-redis-exporter`

앱은 로컬에서 직접 띄우고 싶다면 인프라만 먼저 실행한 뒤 `bootRun` 으로 올리면 된다.

```bash
docker compose up -d
./gradlew bootRun
```

## 1. Smoke Test

전체 벤치마크를 돌리기 전에 작은 부하로 한 번 확인한다.

Redis 경로 smoke test:

```bash
BASE_URL=http://localhost:8080 \
STRATEGY=redis \
VUS=5 \
TOTAL_COUNT=5 \
RUN_LABEL=redis-smoke \
RESULTS_DIR=perf/results/manual \
k6 run perf/k6/coupon-issue-burst.js
```

비관적 락 경로 smoke test:

```bash
BASE_URL=http://localhost:8080 \
STRATEGY=pessimistic \
VUS=5 \
TOTAL_COUNT=5 \
RUN_LABEL=pessimistic-smoke \
RESULTS_DIR=perf/results/manual \
k6 run perf/k6/coupon-issue-burst.js
```

정상 기준:

- 요청 수: `5`
- 성공 수: `5`
- `5xx`: `0`
- 최종 `issuedCount`: `5`
- 결과 파일 생성

생성 위치:

- `perf/results/manual/redis-smoke-summary.json`
- `perf/results/manual/pessimistic-smoke-summary.json`

`perf/results/manual/` 디렉터리를 지운 상태라면 먼저 아래 명령으로 다시 만든다.

```bash
mkdir -p perf/results/manual
```

## 2. 전체 비교 벤치마크

전체 비교는 래퍼 스크립트로 실행한다.

```bash
BASE_URL=http://localhost:8080 perf/k6/run-benchmarks.sh
```

이 스크립트는 실행 전에 필요한 사용자 계정과 access token fixture 를 한 번만 준비한 뒤, 이후 모든 run 에서 재사용한다.

이 스크립트는 아래 매트릭스를 자동 실행한다.

- 전략 2개: `redis`, `pessimistic`
- 시나리오 5개:
  - `50 VU / stock 100`
  - `100 VU / stock 100`
  - `200 VU / stock 100`
  - `500 VU / stock 100`
  - `500 VU / stock 500`
- 반복 3회
- 총 30회 측정

또한 각 측정 전에 warm-up 을 1회 수행한다.

## 3. 기대 결과

기본 매트릭스에는 `stock 100`, `stock 500` 시나리오가 함께 포함된다.

| VU | 기대 성공(201) | 기대 품절(409) | 기대 최종 issuedCount |
|---|---:|---:|---:|
| 50 | 50 | 0 | 50 |
| 100 | 100 | 0 | 100 |
| 200 | 100 | 100 | 100 |

추가 기준:

| VU | 재고 | 기대 성공(201) | 기대 품절(409) | 기대 최종 issuedCount |
|---|---:|---:|---:|---:|
| 500 | 100 | 100 | 400 | 100 |
| 500 | 500 | 500 | 0 | 500 |

모든 run 에서 아래 조건을 만족해야 한다.

- 중복 발급 `400`: `0`
- 기타 `4xx`: `0`
- `5xx`: `0`
- 최종 `issuedCount = min(VUS, stock)`

## 4. 결과 확인

실행 결과는 `perf/results/<timestamp>/` 아래에 생성된다.

- `*-raw.ndjson`: `k6` 원본 이벤트
- `*-summary.json`: 실행별 요약
- `*-summary.txt`: 실행별 텍스트 요약
- `summary.csv`: 전체 실행 요약 테이블
- `report.md`: 전략별 비교 리포트

가장 먼저 볼 파일:

- `perf/results/<timestamp>/report.md`
- `perf/results/<timestamp>/summary.csv`

`report.md` 에는 아래 비교가 정리된다.

- 부하별 평균 `p95`
- 부하별 평균 `p99`
- 부하별 평균 throughput
- Redis 전략 개선율

DB 자원 사용량을 같이 보려면 [docs/performance/monitoring-guide.md](./performance/monitoring-guide.md) 를 참고한다.

수동으로 고부하 단일 run 만 돌리고 싶다면 먼저 사용자 fixture 를 만든다.

```bash
BASE_URL=http://localhost:8080 \
OUTPUT_FILE=perf/results/manual/users-500.json \
USER_COUNT=500 \
perf/k6/prepare-user-fixture.sh
```

그 다음 fixture 를 재사용해 실행한다.

```bash
BASE_URL=http://localhost:8080 \
USER_FIXTURE=perf/results/manual/users-500.json \
STRATEGY=redis \
VUS=500 \
TOTAL_COUNT=100 \
RUN_LABEL=redis-v500-stock100 \
RESULTS_DIR=perf/results/manual \
k6 run perf/k6/coupon-issue-burst.js
```

## 5. 실패 시 확인 포인트

### 앱이 안 뜨는 경우

- `docker compose ps`
- `docker compose logs app`

### MySQL 또는 Redis 기동이 안 되는 경우

- `docker compose logs mysql`
- `docker compose logs redis`

### `k6` 실행 직후 연결 실패가 나는 경우

- `coupon-toy-app` 컨테이너가 `Up` 상태인지 확인
- 포트 `8080` 이 다른 프로세스와 충돌하지 않는지 확인
- 로컬 앱 실행 방식이면 `./gradlew bootRun` 로그에서 부팅 완료 여부 확인

### 결과가 기대값과 다른 경우

아래 파일을 우선 본다.

- `perf/results/<timestamp>/summary.csv`
- `perf/results/<timestamp>/report.md`
- 해당 run 의 `*-summary.json`

## 6. 종료 및 정리

Compose 로 올린 리소스를 내릴 때:

```bash
docker compose down
```

볼륨까지 지우고 완전히 초기화하려면:

```bash
docker compose down -v
```

## 빠른 실행 순서

```bash
docker compose up --build -d
BASE_URL=http://localhost:8080 perf/k6/run-benchmarks.sh
```

결과 확인:

```bash
ls -1 perf/results
```
