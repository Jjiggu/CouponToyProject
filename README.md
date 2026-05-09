# CouponToyProject

대규모 트래픽 환경에서 쿠폰 발급 API가 어떤 문제를 겪는지 재현하고, 발급 구조를 개선했을 때 처리량, 응답 시간, 정합성이 얼마나 좋아지는지 확인하기 위한 Spring Boot 토이 프로젝트입니다.

단순한 쿠폰 CRUD가 아니라, 한정 수량 쿠폰에 많은 사용자가 동시에 접근하는 상황을 기준선으로 삼고 개선 전후를 비교하는 것이 핵심입니다.

## 프로젝트 목표

- 동시에 많은 사용자가 쿠폰 발급을 요청하는 상황을 재현한다.
- 기준선 구현에서 발생하는 병목과 정합성 문제를 확인한다.
- 쿠폰 발급 구조를 단계적으로 개선한다.
- 개선 전후의 수치를 비교해 실제 개선 폭을 기록한다.

주요 비교 대상:

- 처리량: 초당 처리 가능한 발급 요청 수
- 응답 시간: 평균, p95, p99 latency
- 정합성: 초과 발급 여부, 중복 발급 여부, 최종 발급 수
- 안정성: 실패율, DB lock wait, deadlock, timeout
- 리소스 사용량: DB connection, CPU, memory

## 핵심 시나리오

한정 수량 쿠폰을 대상으로 다수의 회원이 동시에 발급을 요청합니다.

예시:

- 쿠폰 수량: 100장
- 동시 요청 사용자: 1,000명
- 기대 결과: 성공 발급은 정확히 100건
- 실패 요청: 수량 소진으로 일관되게 실패
- 검증 대상: 초과 발급 없이 응답 시간이 얼마나 낮은지

## 현재 기준선 구현

현재 구현은 JPA 엔티티를 조회한 뒤 쿠폰 발급 수량을 증가시키고, 발급 이력을 저장하는 방식입니다.

```text
1. 인증된 회원 조회
2. 쿠폰 조회
3. coupon.issuedCount 증가
4. coupon_issues 발급 이력 저장
5. 트랜잭션 커밋
```

이 방식은 구현이 단순하지만, 대규모 동시 요청에서는 다음 문제가 발생할 수 있습니다.

- 동시에 같은 쿠폰 row를 읽고 갱신하면서 lost update가 발생할 수 있다.
- `totalCount`와 `issuedCount` 비교가 원자적으로 보장되지 않으면 초과 발급 위험이 있다.
- 요청이 늘어날수록 DB row 경합과 connection 사용량이 증가한다.
- 같은 회원의 중복 발급을 DB 제약으로 강하게 막고 있지 않다.
- 발급 요청 처리와 발급 이력 저장이 한 트랜잭션에 묶여 있어 쓰기 부하가 직접 API latency로 이어진다.

현재 기준선은 위 문제를 관찰하고 개선 효과를 비교하기 위한 출발점입니다.

## 개선 방향

### 1. DB 원자적 업데이트

쿠폰 row를 읽어서 애플리케이션에서 수량을 증가시키는 대신, DB에서 조건부 update를 수행합니다.

```sql
UPDATE coupons
SET issued_count = issued_count + 1
WHERE coupon_id = ?
  AND issued_count < total_count;
```

검증 포인트:

- 초과 발급 방지
- lost update 방지
- 기준선 대비 p95/p99 latency 변화
- DB lock wait 증가 여부

### 2. 중복 발급 방지

회원과 쿠폰 조합에 unique constraint를 추가해 같은 회원이 같은 쿠폰을 두 번 받을 수 없게 합니다.

```text
UNIQUE(member_id, coupon_id)
```

검증 포인트:

- 동일 회원 반복 요청 시 발급 이력 중복 생성 방지
- 애플리케이션 검증과 DB 제약 간 역할 분리
- 중복 요청이 많은 상황에서 실패 응답 일관성 확인

### 3. Lock 전략 비교

쿠폰 수량 차감 로직에 대해 비관적 락, 낙관적 락, 원자적 update 방식을 비교할 수 있습니다.

비교 항목:

- 성공 발급 수가 정확히 제한되는지
- 충돌이 많은 상황에서 재시도 비용이 어느 정도인지
- lock wait, deadlock, timeout이 발생하는지
- 트래픽 증가에 따라 처리량이 어떻게 떨어지는지

### 4. Redis 기반 선착순 처리

쿠폰 재고 차감을 Redis의 atomic operation으로 먼저 처리하고, 성공한 요청만 DB에 발급 이력을 저장하는 구조를 검토합니다.

검증 포인트:

- DB write 부하 감소
- API 응답 시간 개선
- Redis와 DB 간 데이터 정합성 보정 방식
- 장애 상황에서 재처리 가능한 구조인지

### 5. 비동기 발급 처리

API 요청에서는 발급 요청을 빠르게 접수하고, 실제 발급 이력 저장은 queue consumer가 처리하는 구조를 검토합니다.

검증 포인트:

- API p95/p99 latency 감소
- 순간 트래픽 흡수 능력
- queue 적체 상황에서 사용자 응답 정책
- 최종 일관성 허용 범위

## 측정 기준

개선 효과는 같은 조건에서 기준선과 개선안을 반복 측정해 비교합니다.

### 고정해야 할 조건

- 쿠폰 총 수량
- 동시 사용자 수
- 총 요청 수
- 애플리케이션 인스턴스 수
- DB connection pool 크기
- DB 스펙과 초기 데이터
- JVM 옵션
- 테스트 도구와 시나리오

### 기록할 지표

| 지표 | 의미 |
| --- | --- |
| Total Requests | 전체 발급 요청 수 |
| Success Count | 실제 발급 성공 수 |
| Fail Count | 발급 실패 수 |
| Over-issued Count | 쿠폰 총 수량을 초과한 발급 수 |
| Duplicate Count | 동일 회원 중복 발급 수 |
| TPS/RPS | 초당 처리량 |
| Avg Latency | 평균 응답 시간 |
| p95 Latency | 상위 5% 요청의 응답 시간 |
| p99 Latency | 상위 1% 요청의 응답 시간 |
| Error Rate | 전체 요청 중 오류 비율 |
| DB Lock Wait | DB row 경합 정도 |

### 개선 폭 계산

```text
처리량 개선 배율 = 개선 후 TPS / 기준선 TPS
응답 시간 개선율 = (기준선 latency - 개선 후 latency) / 기준선 latency * 100
정합성 개선 = 초과 발급 수, 중복 발급 수가 0인지 확인
```

## 측정 결과 기록

아직 실제 부하 테스트 결과는 README에 고정하지 않았습니다. 개선안을 적용하면서 아래 표에 같은 조건의 결과를 기록합니다.

| 버전 | 전략 | 동시 사용자 | 쿠폰 수량 | TPS/RPS | Avg | p95 | p99 | 성공 | 실패 | 초과 발급 | 중복 발급 | 비고 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| v0 | JPA 조회 후 수량 증가 | - | - | - | - | - | - | - | - | - | - | 기준선 |
| v1 | DB 조건부 원자 update | - | - | - | - | - | - | - | - | - | - | 예정 |
| v2 | Lock 전략 적용 | - | - | - | - | - | - | - | - | - | - | 예정 |
| v3 | Redis 선차감 | - | - | - | - | - | - | - | - | - | - | 예정 |
| v4 | Queue 기반 비동기 처리 | - | - | - | - | - | - | - | - | - | - | 예정 |

## 현재 구현 기능

- 회원 가입
  - 이메일 중복 검증
  - BCrypt 비밀번호 암호화
  - 기본 권한 `MEMBER` 부여
- 로그인
  - Spring Security 인증
  - JWT access token 발급
  - 토큰 만료 기간 7일
- 쿠폰 관리
  - 쿠폰 생성
  - 쿠폰 단건 조회
- 쿠폰 발급
  - 인증된 회원에게 쿠폰 발급
  - 발급 이력 저장
  - 쿠폰 발급 수량 증가
- 공통 처리
  - JPA Auditing 기반 생성/수정 시간 관리
  - 공통 API 응답 래퍼
  - 전역 예외 처리

## 기술 스택

- Java 17
- Spring Boot 3.4.1
- Spring Web
- Spring Data JPA
- Spring Security
- JWT (`com.auth0:java-jwt`)
- MySQL Connector/J
- Thymeleaf
- Lombok
- JUnit 5

## 프로젝트 구조

```text
src/main/java/couponToy/CouponToyProject
├── Coupon
│   ├── controller
│   ├── dto
│   ├── model
│   ├── repository
│   └── service
├── CouponIssue
│   ├── controller
│   ├── dto
│   ├── model
│   ├── repository
│   └── service
├── Member
│   ├── controller
│   ├── dto
│   ├── model
│   ├── repository
│   └── service
└── global
    ├── api
    ├── config
    ├── constant
    ├── entity
    ├── exception
    └── security
```

## 실행 준비

저장소에는 DB 접속 설정 파일이 포함되어 있지 않습니다. 로컬 실행 전 `src/main/resources/application.yml` 또는 `application.properties`를 추가해 MySQL datasource를 설정해야 합니다.

예시:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/coupon_toy
    username: root
    password: password
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        format_sql: true
    open-in-view: false
```

## 실행 방법

```bash
./gradlew bootRun
```

테스트 실행:

```bash
./gradlew test
```

## 인증

`POST /members/signup`, `POST /members/login`만 인증 없이 접근할 수 있습니다. 그 외 모든 API는 `Authorization` 헤더에 Bearer token이 필요합니다.

```http
Authorization: Bearer {accessToken}
```

## API

### 회원 가입

```http
POST /members/signup
Content-Type: application/json
```

요청:

```json
{
  "email": "user@example.com",
  "password": "password123!",
  "name": "홍길동"
}
```

검증 규칙:

- `email`: 이메일 형식
- `password`: 8~20자, 영문/숫자/특수문자 포함, 공백 불가
- `name`: 영문 또는 한글 2~12자

응답 상태: `201 Created`

### 로그인

```http
POST /members/login
Content-Type: application/json
```

요청:

```json
{
  "email": "user@example.com",
  "password": "password123!"
}
```

응답 상태: `201 Created`

응답의 `accessToken` 값을 이후 인증 API 호출에 사용합니다.

### 쿠폰 생성

```http
POST /coupons
Authorization: Bearer {accessToken}
Content-Type: application/json
```

요청:

```json
{
  "name": "신규 가입 쿠폰",
  "totalCount": 100
}
```

검증 규칙:

- `name`: 필수
- `totalCount`: 필수, 양수

응답 상태: `201 Created`

### 쿠폰 조회

```http
GET /coupons/{couponId}
Authorization: Bearer {accessToken}
```

응답 상태: `200 OK`

### 쿠폰 발급

```http
POST /coupons/issue/{couponId}
Authorization: Bearer {accessToken}
Content-Type: application/json
```

요청:

```json
{}
```

현재 구현에서는 요청 본문보다 인증된 회원 정보와 path variable의 `couponId`를 기준으로 발급합니다.

응답 상태: `201 Created`

## 공통 성공 응답 형식

```json
{
  "success": true,
  "response": {
    "...": "..."
  }
}
```

## 도메인 모델

### Member

- `memberId`
- `email`
- `password`
- `name`
- `role`

### Coupon

- `couponId`
- `name`
- `totalCount`
- `issuedCount`
- `createdAt`
- `updatedAt`

### IssueCoupon

- `issuedId`
- `member`
- `coupon`
- `createdAt`
- `updatedAt`

## 테스트

현재 테스트는 회원 저장, 쿠폰 발급, 순차 발급, 동시 발급 시나리오를 포함합니다.

- `MemberServiceTest`
- `IssueCouponServiceTest`

테스트도 MySQL datasource 설정에 의존합니다. `@AutoConfigureTestDatabase(replace = NONE)` 설정이 있어 별도 테스트 DB 또는 로컬 DB가 준비되어 있어야 합니다.

현재 테스트는 동시성 문제를 관찰하기 위한 출발점입니다. 실제 대규모 트래픽 개선 폭은 JMeter, k6, Gatling 같은 부하 테스트 도구를 추가해 같은 조건에서 반복 측정하는 방식으로 확인하는 것이 적합합니다.

## 현재 구현 메모

- JWT secret key는 코드에 고정되어 있어 운영 환경에서는 외부 설정으로 분리해야 합니다.
- 쿠폰 발급 수량 제한과 동시성 제어는 아직 완성된 정책으로 고정되어 있지 않습니다.
- 쿠폰 조회에서 존재하지 않는 쿠폰 처리 로직은 별도 커스텀 예외로 정리할 여지가 있습니다.
