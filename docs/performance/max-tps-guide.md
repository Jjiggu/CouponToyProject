# 최대 TPS 측정 가이드

쿠폰 발급 API의 최대 유효 TPS를 측정하기 위한 가이드다. 기존 burst 비교와 달리 `constant-arrival-rate` 기반으로 일정 TPS를 유지시키며, 어느 시점까지 `201` 정합성과 무오류 조건을 만족하는지 확인한다.

## 목적

- 전략별 최대 유효 TPS 확인
- `5xx = 0`, `dropped_iterations = 0`, `finalIssuedCount = expectedRequests` 조건을 만족하는지 검증
- Prometheus 가 떠 있으면 run 구간별 모니터링 지표도 함께 저장

## 실행

먼저 애플리케이션과 모니터링을 띄운다.

```bash
docker compose up --build -d
```

기본 실행:

```bash
BASE_URL=http://localhost:8080 perf/k6/run-max-tps.sh
```

권장 반복 측정:

```bash
RATES="100 150 200 250 300" \
REPEATS=3 \
BASE_URL=http://localhost:8080 \
USER_FIXTURE=perf/results/manual/users-10001.json \
perf/k6/run-max-tps.sh
```

준비된 user fixture 재사용:

```bash
BASE_URL=http://localhost:8080 \
USER_FIXTURE=perf/results/manual/users-10001.json \
perf/k6/run-max-tps.sh
```

fixture를 미리 만들고 싶으면:

```bash
BASE_URL=http://localhost:8080 \
OUTPUT_FILE=perf/results/manual/users-10001.json \
USER_COUNT=10001 \
perf/k6/prepare-user-fixture.sh
```

## 기본 매트릭스

- 전략: `pessimistic`, `redis`
- 목표 TPS: `100 200 300 400 500 700 1000`
- duration: `10s`
- stock: `5000`, `10000`

## 조절 가능한 환경 변수

- `RATES`
  - 예: `RATES="200 400 600 800"`
- `DURATION_SECONDS`
  - 예: `DURATION_SECONDS=15`
- `STOCKS`
  - 예: `STOCKS="5000 10000"`
- `STRATEGIES`
  - 예: `STRATEGIES="redis"`
- `REPEATS`
  - 같은 `rate`를 반복 측정할 횟수
  - 예: `REPEATS=3`
- `USER_FIXTURE`
  - 이미 만들어둔 사용자 fixture 경로
  - 파일이 있고 사용자 수가 충분하면 생성 단계를 건너뜀

예시:

```bash
RATES="200 400 600" \
STOCKS="6000" \
DURATION_SECONDS=10 \
BASE_URL=http://localhost:8080 \
perf/k6/run-max-tps.sh
```

## 결과 파일

실행 결과는 `perf/results/<timestamp>-max-tps/` 아래에 생성된다.

- `max-tps-summary.csv`
- `max-tps-report.md`
- `users-fixture.json`
- `monitoring-summary.csv`
- `monitoring-report.md`

`max-tps-report.md`에는 아래가 포함된다.

- 모든 반복을 통과한 기준의 `Max Consistent TPS`
- 시나리오별 평균 `p95`, `throughput`, `dropped iterations`
- 개별 run 결과

실제 측정 결과와 해석 예시는 [max-tps-results.md](./max-tps-results.md) 를 참고한다.

## 유효 판정 기준

각 run 은 아래 조건을 모두 만족해야 유효로 본다.

- `status201 >= expectedRequests`
- `status201 - expectedRequests <= USER_BUFFER`
- `status400 == 0`
- `status409 == 0`
- `status5xx == 0`
- `droppedIterations == 0`
- `contractFailures == 0`
- `finalIssuedCount == status201`

즉, 최대 TPS는 단순 최고 처리량이 아니라 위 조건을 만족한 가장 높은 목표 TPS다.

주의:

- `stock` 은 반드시 `targetRate * durationSeconds` 보다 크거나 같아야 한다.
- 예를 들어 `1000 TPS` 를 `10s` 동안 측정하면 최소 `10000` 장이 필요하다.
