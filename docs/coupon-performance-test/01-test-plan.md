# 01. 테스트 계획

## 목적

쿠폰 발급 방식별로 같은 조건에서 성능, 정합성, 병목 원인을 비교한다.

비교 대상은 다음 세 가지다.

- 비관적 락: DB row lock으로 수량과 중복 발급을 제어
- Redis Transactional: Redis `SET + WATCH + MULTI/EXEC`으로 선착순 입장 제어 후 DB 저장
- Redis + Spring Event: Redis 입장 제어 후 Spring Event로 DB 저장을 비동기 처리

## 측정 목표

- 최대 안정 처리량 RPS
- 응답 지연 p50/p95/p99
- 성공, 중복, 품절, 5xx 비율
- 쿠폰 과발급 여부
- 1인 1장 중복 발급 차단 여부
- App/MySQL/Redis 자원 사용률
- 병목 발생 지점과 원인
- 비동기 방식의 접수 후 DB 최종 반영 지연

## 테스트 환경

테스트 환경은 로컬 Docker Compose 고정 환경으로 한다.

| 대상 | 제한 |
|---|---:|
| Spring App | `1 CPU`, `1GB` |
| MySQL | `1 CPU`, `1GB` |
| Redis | `0.5 CPU`, `512MB` |

k6는 Docker Compose 내부가 아니라 host에서 실행한다. k6 host CPU가 80%를 넘거나 `dropped_iterations`가 1%를 넘는 결과는 무효로 본다.

## 공통 데이터

각 전략은 독립 쿠폰을 사용한다.

| couponId | 전략 | totalCount |
|---:|---|---:|
| `1` | 비관적 락 | `10000` |
| `2` | Redis Transactional | `10000` |
| `3` | Redis + Spring Event | `10000` |

테스트 회원은 `100000`명으로 고정한다. k6는 미리 생성한 JWT token CSV를 사용한다.

## k6 시나리오

| 시나리오 | 목적 | 조건 |
|---|---|---|
| Smoke | 기본 연결과 응답 검증 | `10 RPS`, 1분 |
| Step load | 한계 RPS 식별 | `100 → 300 → 500 → 800 → 1000 → 1500 → 2000 RPS`, 각 3분 |
| Spike | 순간 트래픽 대응 확인 | `100 RPS` 유지 중 30초간 `2000 RPS` |
| Soak | 지속 부하 안정성 확인 | 최대 안정 RPS의 70%, 30분 |
| Sold-out | 품절 이후 빠른 실패 확인 | `totalCount=1000`, `5000 requests`, `500 RPS` |
| Duplicate | 중복 발급 차단 확인 | `20000 requests`, unique user 5000명 반복 |

## 안정 처리량 기준

아래 조건을 모두 만족하는 가장 높은 RPS를 최대 안정 처리량으로 본다.

- 기술 실패율: `5xx + timeout < 0.1%`
- k6 dropped iterations: `< 1%`
- 동기 API p95: `500ms 이하`
- 비동기 API 접수 p95: `100ms 이하`
- 비동기 DB 최종 반영 p95 lag: `5s 이하`
- 정합성 검증 결과: `PASS`

## 결과 비교 표

| 전략 | 최대 안정 RPS | p95 | p99 | 실패율 | DB CPU | Redis CPU | Hikari pending | Row lock wait | 정합성 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 비관적 락 |  |  |  |  |  |  |  |  |  |
| Redis Transactional |  |  |  |  |  |  |  |  |  |
| Redis + Spring Event |  |  |  |  |  |  |  |  |  |
