# 선착순 쿠폰 발급 시스템

고동시성 환경에서 선착순 쿠폰을 정확하게 발급하는 토이 프로젝트.

**핵심 불변식**
- 지급 수량을 절대 초과하지 않는다
- 동일 사용자에게 1장만 발급한다

## 학습 단계

동시성 제어와 비동기 처리를 한 단계씩 구현하고 직접 실행해 확인한다. 계획, 실험 API(`/lab`, `local` 프로필 전용), 단계별 결과는 [docs/learning](docs/learning/README.md) 에 있다.

- [1단계: 명시적 락 없이 격리 수준 비교](docs/learning/01-isolation-level.md)

## 기술 스택

| 분류 | 기술 |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.4.1, Spring Data JPA, Spring Data Redis |
| Database | MySQL 8.0.36 |
| Cache | Redis 7.2 |
| Test | JUnit 5, k6 |

## 실행 방법

기본 설정은 `src/main/resources/application.yml`(git 미추적) 에 있다. 기본 프로필은 `local` 이다.

- MySQL: `localhost:3306`, DB `CouponToyProject`
- Redis: `localhost:6379`
- 테스트: `CouponToyProject_test` DB(create-drop), Redis db 1

```bash
docker compose up -d mysql redis
./gradlew bootRun
./gradlew test
```

`DELETE /lab/data` 는 연결된 DB 의 쿠폰·발급·회원 테이블을 TRUNCATE 한다. 기존 데이터를 남기려면 별도 스키마로 띄운다.

```bash
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/CouponToyProject_lab?createDatabaseIfNotExist=true' \
SPRING_DATA_REDIS_DATABASE=2 ./gradlew bootRun
```

## API (`local` 프로필 전용, 인증 없음)

| Method | Path | Body | 설명 |
|---|---|---|---|
| POST | `/lab/members` | `{"count": 1000}` | 실험용 회원 일괄 생성 |
| POST | `/lab/coupons` | `{"name": "s1", "totalCount": 100}` | 실험용 쿠폰 생성 |
| POST | `/lab/coupons/{couponId}/issue` | `{"memberId": 1, "method": "SERIALIZABLE"}` | 방식 선택 발급: `REPEATABLE_READ`, `SERIALIZABLE`, `PESSIMISTIC`, `REDIS_TRANSACTIONAL`, `REDIS_EVENT`(202 접수) |
| GET | `/lab/coupons/{couponId}/result` | | 발급 결과, 수량, 검증 기준, 순서, 지연 |
| DELETE | `/lab/data` | | 실험 데이터 초기화 |

모든 응답은 아래 형태로 반환된다. 품절·중복은 409, 데드락·락 대기 초과는 503, 잘못된 입력은 400.

```json
{ "success": true,  "response": { }, "error": null }
{ "success": false, "response": null, "error": { "message": "...", "status": 409 } }
```

## Redis 발급 플로우 (`method: REDIS_TRANSACTIONAL`, 3단계 기준선)

```
IssueCouponService.issueCoupon()

  [1] Redis GET coupon:total:{couponId}          totalCount 캐시 조회
      └─ 캐시 미스 → DB 조회 후 재캐싱 (자가 복구)

  [2] Redis MULTI/EXEC
      SCARD coupon:issued:{couponId}             현재 발급 수
      SADD  coupon:issued:{couponId}  {userId}   중복 등록 시도
      ─────────────────────────────────────────
      SADD == 0          → 409 IsAlreadyIssued   (DB 미접근)
      SCARD >= totalCount → 409 CouponSoldOut    (SREM 후 예외)

  [3] coupon_issues INSERT                       UNIQUE(coupon_id, member_id)
      UPDATE coupons SET issued_count = issued_count + 1
       WHERE coupon_id = ? AND issued_count < total_count   조건부 UPDATE, 0건이면 품절

  [4] 예외 발생 시 → SREM (Redis 롤백)
```

## Redis 키

| 키 | 자료형 | 설명 |
|---|---|---|
| `coupon:total:{couponId}` | String | 총 발급 수량 (쿠폰 생성 시 웜업, 불변) |
| `coupon:issued:{couponId}` | SET | 발급된 userId 집합 |

## 이전 문서 (보존용)

학습 정리 이전 구조(JWT 인증, 이전 엔드포인트) 기준 문서와 결과다. 현재 코드와 다를 수 있다.

- [docs/overview.md](./docs/overview.md) — 시스템 개요, DB 스키마
- [docs/auth.md](./docs/auth.md) — 인증 플로우
- [docs/coupon-issue.md](./docs/coupon-issue.md) — 쿠폰 발급 플로우 상세
- [docs/error-handling.md](./docs/error-handling.md) — 예외 처리
- [docs/test-guide.md](./docs/test-guide.md) — 테스트 실행 순서와 결과 확인 방법
- [docs/performance/coupon-issue-comparison.md](./docs/performance/coupon-issue-comparison.md) — `k6` 성능 비교 가이드
- [docs/performance/monitoring-guide.md](./docs/performance/monitoring-guide.md) — Grafana/Prometheus 모니터링 가이드
- [docs/performance/max-tps-guide.md](./docs/performance/max-tps-guide.md) — 최대 TPS 측정 가이드
- [docs/performance/max-tps-results.md](./docs/performance/max-tps-results.md) — 최대 TPS 측정 결과와 해석

## Coupon Performance Test Docs

쿠폰 발급 성능/부하 테스트 문서는 [docs/coupon-performance-test](docs/coupon-performance-test/README.md)에 정리되어 있습니다.
