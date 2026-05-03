# 최대 TPS 측정 결과

`constant-arrival-rate` 기반 `k6` 측정으로 Redis 선검사 전략과 비관적 락 전용 전략의 최대 유효 TPS를 비교한 결과다.

## 측정 목적

- 두 전략이 오류 없이 안정적으로 유지할 수 있는 최대 TPS 확인
- 반복 측정 기준으로 일관되게 재현되는 처리량 한계 파악
- burst 테스트와 별개로 sustained throughput 관점의 차이 확인

## 측정 조건

- 전략:
  - `pessimistic`
  - `redis`
- 목표 TPS:
  - `100`, `150`, `200`, `250`, `300`
- 반복 횟수:
  - 각 구간 `3회`
- duration:
  - `10초`
- stock:
  - `5000`, `10000`
- 유효 판정 기준:
  - `status201 >= expectedRequests`
  - `status201 - expectedRequests <= USER_BUFFER`
  - `status400 == 0`
  - `status409 == 0`
  - `status5xx == 0`
  - `droppedIterations == 0`
  - `contractFailures == 0`
  - `finalIssuedCount == status201`

`1001`, `1501` 같은 값은 `constant-arrival-rate` 경계 시점에서 발생하는 정상적인 `+1 overshoot`다.

## 요약 결과

| Stock | Strategy | Max Consistent TPS |
|---:|---|---:|
| 5000 | pessimistic | 100 |
| 5000 | redis | 150 |
| 10000 | pessimistic | 100 |
| 10000 | redis | 150 |

핵심 결론:

- 비관적 락 전용 전략은 반복 3회 기준 `100 TPS`까지 안정적으로 유지됐다.
- Redis 선검사 전략은 반복 3회 기준 `150 TPS`까지 안정적으로 유지됐다.
- 현재 환경에서 Redis 전략은 최대 유효 TPS를 `100 -> 150`으로 높였다.
- 즉, sustained throughput 기준으로 약 `50%` 상향 효과가 관찰됐다.

## 시나리오별 평균

| Stock | Strategy | Target TPS | Runs | Valid Runs | Avg p95(ms) | Avg Throughput | Avg Dropped |
|---:|---|---:|---:|---:|---:|---:|---:|
| 5000 | pessimistic | 100 | 3 | 3 | 90.18 | 97.62 | 0.00 |
| 5000 | pessimistic | 150 | 3 | 0 | 5887.58 | 72.11 | 523.00 |
| 5000 | redis | 100 | 3 | 3 | 22.07 | 98.47 | 0.00 |
| 5000 | redis | 150 | 3 | 3 | 293.08 | 146.83 | 0.00 |
| 10000 | pessimistic | 100 | 3 | 3 | 13.75 | 98.74 | 0.00 |
| 10000 | pessimistic | 150 | 3 | 0 | 7192.84 | 53.91 | 698.67 |
| 10000 | redis | 100 | 3 | 3 | 63.66 | 98.26 | 0.00 |
| 10000 | redis | 150 | 3 | 3 | 8.39 | 147.83 | 0.00 |

## 해석

### 1. 100 TPS 구간

- 두 전략 모두 `3/3` 반복을 통과했다.
- throughput도 둘 다 `98~99 TPS` 수준으로 안정적이었다.
- 즉 `100 TPS`는 현재 환경에서 두 전략 모두 충분히 처리 가능한 구간이다.

### 2. 150 TPS 구간

- `pessimistic`는 `3/3` 반복 모두 실패했다.
- `redis`는 `3/3` 반복 모두 통과했다.
- 비관적 락 전용 전략은 이 구간에서 `droppedIterations`가 대량 발생했고, 목표 처리량을 유지하지 못했다.
- Redis 전략은 동일 조건에서 `droppedIterations = 0`을 유지하며 목표 TPS에 근접한 throughput을 보였다.

즉 이번 실험에서 두 전략의 처리량 상한은 `150 TPS` 구간에서 명확하게 갈렸다.

### 3. dropped iterations의 의미

이번 결과에서 실패를 판단할 때 가장 중요한 지표는 `p95`보다 `droppedIterations`와 실제 throughput이다.

- `5xx`는 없었다.
- 그러나 비관적 락 전용 전략은 `150 TPS`에서 scheduler가 요청을 제때 투입하지 못했다.
- 이는 단순 응답 지연을 넘어서, 해당 TPS를 지속 처리할 수 있는 용량 한계를 드러낸다.

## 왜 Redis가 더 높은 TPS를 유지했는가

현재 Redis 전략은 Redis만으로 끝나는 구조는 아니다. 성공 요청은 결국 DB 비관적 락을 타고 최종 저장까지 수행한다.

그럼에도 TPS 차이가 난 이유는 다음처럼 해석할 수 있다.

- Redis 선검사가 일부 요청을 앞단에서 정리한다.
- DB에 도달하는 요청의 경합 밀도를 낮춘다.
- 그 결과 비관적 락 전용 전략보다 더 높은 입력률에서도 scheduler drop 없이 버틸 수 있다.

즉 현재 구조의 강점은 "DB 쓰기를 제거했다"가 아니라, "DB 경합이 한계에 도달하기 전까지의 완충 구간을 넓혔다"에 가깝다.

## 주의할 점

- 본 측정은 로컬 단일 환경 기준이다.
- `p95` 값은 반복별 편차가 있으므로 절대값보다 추세를 보는 편이 안전하다.
- 특히 성공 run 간 `p95`는 런타임 편차 영향을 받기 쉽다.
- 이번 결과에서 더 신뢰할 지표는 아래 세 가지다.
  - `valid / invalid`
  - `droppedIterations`
  - `actual throughput`

## 결론

- 비관적 락 전용 전략의 최대 유효 TPS는 `100`
- Redis 선검사 전략의 최대 유효 TPS는 `150`
- 현재 구조에서 Redis 도입은 sustained throughput 기준 약 `50%` 개선 효과를 보였다.
- 따라서 이번 실험 범위에서는 Redis 전략이 비관적 락 전용 전략보다 더 높은 처리량 상한을 제공한다고 결론낼 수 있다.

## 다음 단계

- `150`, `175`, `200`, `225` 구간으로 Redis 한계점을 더 촘촘히 재탐색
- Grafana에서 `MySQL Threads`, `MySQL CPU`, `Redis OPS`를 같은 구간에 겹쳐 확인
- 단일 로컬 환경이 아닌 분리된 부하 생성 환경에서 재측정
