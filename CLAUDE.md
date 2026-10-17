# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

선착순 쿠폰 발급 처리 토이 프로젝트. Spring Boot + MySQL + Redis(SET) 기반으로 고동시성 쿠폰 발급을 구현한다.

## Commands

### Build & Run
```bash
./gradlew build               # 전체 빌드
./gradlew bootRun             # 애플리케이션 실행
```

### Tests
```bash
./gradlew test                                          # 전체 테스트 실행
./gradlew test --tests "*.IsolationIssueCouponServiceTest"                      # 특정 테스트 클래스 실행
./gradlew test --tests "*.IsolationIssueCouponServiceTest.concurrentRequests"     # 단일 테스트 실행
LAB_CHANGE_MYSQL_GLOBALS=true ./gradlew test --tests "*.IsolationLockSettingsExperimentTest"  # MySQL 전역 락 설정을 바꾸는 실험 (끝나면 복구)
```

> 통합 테스트(`@SpringBootTest`)는 MySQL과 Redis가 로컬에서 실행 중이어야 한다. 테스트는 `CouponToyProject_test` DB와 Redis db 1을 쓴다.
> 단계별 실행 기록은 `build/lab/stage1-isolation.md`, `build/lab/stage1-lock-settings.md`, `build/lab/stage2-lock.md` 에 남는다. 학습 단계 문서는 `docs/learning/`.

### Infrastructure (Docker)
```bash
# src/main/resources/docker-compose.yml 기준
docker compose -f src/main/resources/docker-compose.yml up -d
```
- MySQL: `localhost:3306`
- Redis: `localhost:6379`

### Docker Image Build
```bash
./gradlew build
docker build -t docker-coupon .
```

## Architecture

### Domain Structure
패키지는 도메인별로 구성되며, 각 도메인은 `controller / service / repository / model / dto` 레이어를 가진다.

- **Member** – 실험용 회원 엔티티 (인증 없음. email/password/role 컬럼은 기존 DB 스키마 호환용)
- **Coupon** – 쿠폰 엔티티와 생성
- **CouponIssue** – 쿠폰 발급. 학습 단계별 비교 구현 (`IsolationIssueCouponService`, `PessimisticIssueCouponService`, `OptimisticIssueCouponService`, `IssueCouponService`(Redis), `EventIssueCouponService`). 낙관적 락은 같은 `coupons` 테이블에 매핑한 `VersionedCoupon`(@Version)만 쓴다. `Coupon` 에는 @Version 을 넣지 않는다
- **Lab** – `local` 프로필 전용 실험 API(`/lab/**`, 인증 없음). 데이터 준비, 방식 선택 발급, 결과 조회, 초기화. 단일 진입점 `LabService.issue()` 가 도착 순서와 결과를 기록

### API Endpoints

| Method | Path | 설명 |
|---|---|---|
| POST | `/lab/members`, `/lab/coupons` | 실험 데이터 준비 (local 전용) |
| POST | `/lab/coupons/{couponId}/issue` | `{memberId, method, duplicateCheck?}` 로 방식 선택 발급 (local 전용). `REDIS_EVENT` 는 202 |
| GET | `/lab/coupons/{couponId}/result` | 검증 기준·순서·지연·락 카운터 (local 전용) |
| DELETE | `/lab/data` | 설정된 DB 3개 테이블 TRUNCATE + Redis `coupon:*` 삭제 (local 전용) |

### Redis 발급 플로우 (`method: REDIS_TRANSACTIONAL`)

```
IssueCouponService.issueCoupon()

  [1] Redis GET coupon:total:{couponId} — 없으면 couponRepository.findById() 후 재캐싱

  [2] CouponIssuedRedisRepository.checkAndRegister() — Redis MULTI/EXEC
        SCARD coupon:issued:{couponId}  → 현재 발급 수
        SADD  coupon:issued:{couponId} userId → 중복 체크
        ───────────────────────────────────────
        SADD == 0 → IsAlreadyIssued (409)          ← DB 미접근
        SCARD >= totalCount → SREM + CouponSoldOutException (409)

  [3] issueCouponRepository.save()               ← UNIQUE(coupon_id, member_id)
        couponRepository.increaseIssuedCount()     ← 조건부 UPDATE (issued_count < total_count), 0건이면 품절

  [4] 예외 발생 시 → CouponIssuedRedisRepository.remove() — Redis 롤백
```

### Redis Key 구조
| 키 패턴 | 자료형 | 용도 |
|---|---|---|
| `coupon:total:{couponId}` | String | 쿠폰 총 발급 수량 캐시 (쿠폰 생성 시 웜업, 불변) |
| `coupon:issued:{couponId}` | SET | 발급된 userId 집합 (중복 방지 + 수량 추적) |

### Concurrency Strategy
- **Redis SET + MULTI/EXEC**: SCARD(수량 체크) + SADD(중복 방지)를 원자적으로 실행 — Redis 단계에서 대부분의 초과·중복 발급을 사전 차단
- **DB 조건부 UPDATE + UNIQUE**: Redis 통과 후 DB 수준에서 최종 수량·중복 검증 (비관적 락은 `PessimisticIssueCouponService` 에만 있음)
- **Redis 롤백**: DB 발급 실패 시 SREM으로 Redis 상태 복구

### Global Infrastructure (`global` 패키지)
- **인증 없음**: 모든 API 는 `Lab` 패키지에 있고 `@Profile("local")` 로만 등록된다
- **API 응답**: `ApiUtils.success()` / `ApiUtils.error()` → `ApiResult<T>` 래퍼
- **예외 처리**: `GlobalControllerAdvice`에서 도메인 예외를 HTTP 응답으로 변환
  - 400: 입력 검증·파싱 오류
  - 404: `CouponNotFoundException`, `MemberNotFoundException`, 없는 경로
  - 409: `CouponSoldOutException`, `IsAlreadyIssued`, `DataIntegrityViolationException`(UNIQUE 위반)
  - 503: `PessimisticLockingFailureException`(데드락, 락 대기 시간 초과), `OptimisticLockingFailureException`(버전 충돌)

### Tech Stack
- Java 17, Spring Boot 3.4.1, Spring Data JPA, Spring Data Redis, Lombok
- MySQL 8.0.36, Redis 7.2
- Test: JUnit 5, k6 (`performance/k6/coupon-issue.js`)

## 행동 지침

> 속도보다 신중함에 무게를 둔다. 사소한 작업에는 판단력을 활용할 것.

### 1. 코딩 전에 생각하라
추측하지 말 것. 혼란을 숨기지 말 것. 트레이드오프를 드러낼 것.

구현 전에:
- 가정을 명시적으로 진술할 것. 불확실하면 물어볼 것.
- 여러 해석이 가능하면 제시할 것 — 조용히 하나를 선택하지 말 것.
- 더 단순한 접근법이 있으면 말할 것. 필요하면 반론을 제기할 것.
- 무언가 불명확하면 멈출 것. 무엇이 혼란스러운지 명명할 것. 질문할 것.

### 2. 단순함 우선
문제를 해결하는 최소한의 코드. 추측성 코드는 없어야 한다.

- 요청받은 것 이상의 기능을 추가하지 말 것.
- 한 번만 쓰이는 코드에 추상화를 만들지 말 것.
- 요청되지 않은 "유연성"이나 "설정 가능성"을 넣지 말 것.
- 발생 불가능한 시나리오에 대한 에러 처리를 하지 말 것.
- 200줄을 작성했는데 50줄로 가능하다면, 다시 작성할 것.
- 스스로에게 물어볼 것: "시니어 엔지니어가 이걸 보고 과하게 복잡하다고 할까?" 그렇다면 단순화할 것.

### 3. 외과적 수정
반드시 수정해야 하는 것만 건드릴 것. 자신이 만든 지저분함만 정리할 것.

기존 코드를 수정할 때:
- 인접한 코드, 주석, 포맷팅을 "개선"하지 말 것.
- 고장나지 않은 것을 리팩터링하지 말 것.
- 본인이라면 다르게 하더라도 기존 스타일에 맞출 것.
- 관련 없는 데드 코드를 발견하면 언급은 하되 — 삭제하지 말 것.

본인의 변경으로 인해 고아 코드가 생겼을 때:
- 본인의 변경으로 인해 사용되지 않게 된 import/변수/함수는 제거할 것.
- 요청받지 않는 한 기존에 있던 데드 코드는 제거하지 말 것.

검증 기준: 변경된 모든 줄은 사용자의 요청에 직접적으로 연결되어야 한다.

### 4. 목표 중심 실행
성공 기준을 정의할 것. 검증될 때까지 반복할 것.

작업을 검증 가능한 목표로 변환할 것:
- "유효성 검사 추가" → "잘못된 입력에 대한 테스트를 작성하고, 통과시킬 것"
- "버그 수정" → "재현하는 테스트를 작성하고, 통과시킬 것"
- "X 리팩터링" → "리팩터링 전후로 테스트가 통과하는지 확인할 것"

다단계 작업의 경우 간략한 계획을 명시할 것:
1. [단계] → 검증: [확인사항]
2. [단계] → 검증: [확인사항]
3. [단계] → 검증: [확인사항]
