# 시스템 개요

## 목차

| 문서 | 내용 |
|---|---|
| [overview.md](./overview.md) | 시스템 개요, DB 스키마, Redis 키 (현재 문서) |
| [auth.md](./auth.md) | 회원가입, 로그인, JWT 검증 플로우 |
| [coupon-issue.md](./coupon-issue.md) | 쿠폰 발급 플로우 상세 |
| [error-handling.md](./error-handling.md) | 예외 처리, HTTP 상태코드 매핑 |

---

## 프로젝트 목표

고동시성 환경에서 선착순 쿠폰을 **정확하게** 발급하는 것.

보장해야 할 두 가지 불변식:
1. 지급 수량을 **절대 초과하지 않는다**
2. 동일 사용자에게 **1장만 발급한다**

---

## 기술 스택

| 분류 | 기술 | 역할 |
|---|---|---|
| Runtime | Java 17 | |
| Framework | Spring Boot 3.4.1 | Web, JPA, Redis, Security 통합 |
| Database | MySQL 8 | 쿠폰·발급 이력 영속 저장 |
| Cache | Redis | totalCount 캐싱 + 발급 사전 체크 |
| Auth | JWT (com.auth0:java-jwt) | Stateless 인증 토큰 (유효기간 7일) |
| Security | Spring Security | 필터 체인, BCrypt 비밀번호 암호화 |
| Test | JUnit 5 | 단위·통합 테스트 |

---

## 실행 설정

기본 실행 설정은 `src/main/resources/application.yml` 에 정의돼 있다.

| 항목 | 기본값 |
|---|---|
| MySQL URL | `jdbc:mysql://localhost:3306/CouponToyProject` |
| MySQL 계정 | `root` |
| Redis | `localhost:6379` |
| JPA DDL | `update` |

통합 테스트는 `src/test/resources/application.yml` 을 사용하며 `CouponToyProject_test` DB를 바라보도록 분리했다. 다만 현재 리팩토링 브랜치의 테스트 코드는 최신 구조 기준이 아니다.

---

## 도메인 구조

```
couponToy.CouponToyProject
├── Member          회원 가입 · 로그인
├── Coupon          쿠폰 생성 · 조회
├── CouponIssue     쿠폰 발급 (핵심 도메인)
└── global
    ├── config      Redis, Security 설정
    ├── security    JWT 필터, MemberDetails
    ├── exception   도메인 예외 + GlobalControllerAdvice
    ├── aop         @Retry + OptimisticLockRetryAspect
    └── api         ApiResult 공통 응답 래퍼
```

각 도메인은 `controller / service / repository / model / dto` 레이어로 구성된다.

---

## DB 스키마

### members

| 컬럼 | 타입 | 설명 |
|---|---|---|
| member_id | BIGINT PK AUTO_INCREMENT | |
| email | VARCHAR NOT NULL | 로그인 ID (unique) |
| password | VARCHAR NOT NULL | BCrypt 해시 |
| name | VARCHAR NOT NULL | |
| role | VARCHAR NOT NULL | MEMBER / ADMIN |
| created_at | DATETIME | |
| updated_at | DATETIME | |

### coupons

| 컬럼 | 타입 | 설명 |
|---|---|---|
| coupon_id | BIGINT PK AUTO_INCREMENT | |
| name | VARCHAR NOT NULL | 쿠폰 이름 |
| total_count | INT NOT NULL | 총 발급 가능 수량 (불변) |
| issued_count | INT NOT NULL DEFAULT 0 | 현재까지 발급된 수량 |
| created_at | DATETIME | |
| updated_at | DATETIME | |

### coupon_issues

| 컬럼 | 타입 | 설명 |
|---|---|---|
| issued_id | BIGINT PK AUTO_INCREMENT | |
| member_id | BIGINT NOT NULL | 발급받은 회원 PK |
| coupon_id | BIGINT NOT NULL | 발급된 쿠폰 PK |
| created_at | DATETIME | 발급 시각 |
| updated_at | DATETIME | |

---

## Redis 키 구조

| 키 패턴 | 자료형 | 값 | 설정 시점 | 용도 |
|---|---|---|---|---|
| `coupon:total:{couponId}` | String | totalCount | 쿠폰 생성 시 | totalCount 캐시 (불변, 만료 없음) |
| `coupon:issued:{couponId}` | SET | userId (String) | 발급 요청 시 | 발급된 사용자 집합 — 중복 방지 + 수량 추적 |

### 캐시 웜업 전략

`coupon:total:{couponId}` 는 쿠폰 생성(`POST /coupons`) 시점에 DB 저장과 함께 Redis에 SET된다.

```
쿠폰 생성
  couponRepository.save()          → coupons 테이블 INSERT
  couponIssuedRedisRepository
    .setTotalCount(couponId, n)    → SET coupon:total:{couponId} n
```

`totalCount` 는 생성 이후 변경되지 않으므로 만료(TTL) 없이 영구 보관한다.
Redis 재시작 등으로 키가 사라지면, 발급 요청 시 DB에서 읽어 자동 재캐싱한다.

---

## API 엔드포인트 목록

| Method | Path | 인증 | 도메인 |
|---|---|---|---|
| POST | `/members/signup` | 불필요 | Member |
| POST | `/members/login` | 불필요 | Member |
| POST | `/coupons` | 필요 | Coupon |
| GET | `/coupons/{couponId}` | 필요 | Coupon |
| POST | `/coupons/issue/{couponId}` | 필요 | CouponIssue |
| POST | `/coupons/issue/pessimistic/{couponId}` | 필요 | CouponIssue |

---

## 공통 응답 형식

모든 API는 `ApiResult<T>` 래퍼로 응답한다.

```json
// 성공
{ "success": true, "response": { }, "error": null }

// 실패
{ "success": false, "response": null, "error": { "message": "...", "status": 400 } }
```
