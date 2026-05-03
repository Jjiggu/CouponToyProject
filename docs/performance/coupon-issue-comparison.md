# 쿠폰 발급 성능 비교

Redis 선검사 발급(`POST /coupons/issue/{couponId}`)과 비교용 비관적 락 전용 발급(`POST /coupons/issue/pessimistic/{couponId}`)을 `k6`로 비교한다.

## 목적

- Redis 기반 사전 차단이 비관적 락 전용 구현 대비 얼마나 개선되는지 정량화
- 고동시성에서 두 전략 모두 초과 발급 없이 동작하는지 검증
- 재현 가능한 벤치마크 자산과 원본 데이터를 남겨 반복 측정 가능하게 구성

## 시나리오

- 단일 쿠폰 버스트
- 시나리오:
  - `50 VU / stock 100`
  - `100 VU / stock 100`
  - `200 VU / stock 100`
  - `500 VU / stock 100`
  - `500 VU / stock 500`
- 각 부하 3회 반복
- 총 30회 실행: `2 strategies x 5 scenarios x 3 repeats`
- 회원가입/로그인/쿠폰생성은 `k6 setup()`에서 수행하고 측정 구간은 발급 요청만 본다

## 실행 방법

서버와 MySQL, Redis를 먼저 띄운다.

```bash
./gradlew bootRun
```

또는 앱까지 포함해 Compose로 한 번에 올릴 수 있다.

```bash
docker compose up --build -d
```

다른 터미널에서 벤치마크를 실행한다.

```bash
BASE_URL=http://localhost:8080 perf/k6/run-benchmarks.sh
```

래퍼 스크립트는 실행 전에 필요한 사용자 계정과 access token fixture 를 한 번만 생성하고, 모든 run 에서 재사용한다. 그래서 `500 VU` 이상에서도 `k6 setup()` 타임아웃 없이 반복 비교를 진행할 수 있다.

단일 smoke test만 확인하려면 아래처럼 실행한다.

```bash
BASE_URL=http://localhost:8080 \
STRATEGY=redis \
VUS=5 \
TOTAL_COUNT=5 \
RUN_LABEL=redis-smoke \
RESULTS_DIR=perf/results/manual \
k6 run perf/k6/coupon-issue-burst.js
```

고부하 단일 run 을 수동으로 돌릴 때는 fixture 를 먼저 만들어두는 편이 낫다.

```bash
BASE_URL=http://localhost:8080 \
OUTPUT_FILE=perf/results/manual/users-500.json \
USER_COUNT=500 \
perf/k6/prepare-user-fixture.sh
```

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

## 산출물

실행 결과는 `perf/results/<timestamp>/` 아래에 생성된다.

- `*-raw.ndjson`: `k6 --out json=...` 원본
- `*-summary.json`: 실행별 요약
- `*-summary.txt`: 실행별 텍스트 요약
- `summary.csv`: 전체 실행 요약
- `report.md`: 실행 묶음 비교 리포트

`perf/results/`는 생성물 디렉터리이며 결과 파일은 기본적으로 Git 추적 대상에서 제외한다.

## 비교 지표

- 전체 요청 수
- `201`, `400`, `409`, `5xx` 건수
- 전체 latency `p50/p95/p99`
- 성공 요청 latency `p50/p95/p99`
- 처리량(`issue_requests.rate`)
- 최종 `issuedCount`

## 기대값

- `50 VU / stock 100`: 성공 `50`, 품절 `0`
- `100 VU / stock 100`: 성공 `100`, 품절 `0`
- `200 VU / stock 100`: 성공 `100`, 품절 `100`
- `500 VU / stock 100`: 성공 `100`, 품절 `400`
- `500 VU / stock 500`: 성공 `500`, 품절 `0`
- 모든 run에서 중복 발급 `0`
- 모든 run에서 최종 `issuedCount = min(VUS, stock)`
- `5xx` 또는 계약 위반이 발생한 run은 invalid 로 본다

## 최신 결과

저장소에는 생성 결과를 커밋하지 않는다. 최신 실행 결과는 가장 최근 `perf/results/<timestamp>/report.md` 를 확인한다.
