# 쿠폰 발급 플로우

고동시성 환경에서 수량 초과·중복 발급을 방지하는 핵심 플로우.  
Redis를 1차 게이트, DB 비관적 락을 최종 안전장치로 사용하는 이중 보호 구조다.

---

## 전체 흐름

```
클라이언트
    │  POST /coupons/issue/{couponId}
    │  Authorization: Bearer {token}
    ▼
JwtAuthenticationFilter         JWT 검증 → SecurityContextHolder 설정
    │
    ▼
IssueCouponController
    │
    ▼
IssueCouponService.issueCoupon()
    │
    ├─ [Step 1]  Redis GET coupon:total:{couponId}
    │            캐시 미스 시 DB 조회 후 재캐싱
    │
    ├─ [Step 2]  Redis MULTI/EXEC
    │            SCARD coupon:issued:{couponId}   현재 발급 수
    │            SADD  coupon:issued:{couponId}   userId 등록 시도
    │            ─────────────────────────────────────────────
    │            SADD == 0           → 400 (중복 발급)
    │            SCARD >= totalCount → 409 (수량 초과) + SREM
    │
    ├─ [Step 3]  SELECT ... FOR UPDATE  (PESSIMISTIC_WRITE)
    │            coupon.increaseIssueAmount()
    │            coupon_issues INSERT
    │
    └─ [Step 4]  예외 발생 시 → SREM (Redis 롤백)
```

---

## Step 1 — totalCount 조회 (캐시 우선)

```java
int totalCount = couponIssuedRedisRepository.getTotalCount(couponId)
        .orElseGet(() -> {
            Coupon c = couponRepository.findById(couponId)
                    .orElseThrow(() -> new CouponNotFoundException(...));
            couponIssuedRedisRepository.setTotalCount(couponId, c.getTotalCount());
            return c.getTotalCount();
        });
```

`coupon:total:{couponId}` 키에서 `GET` 으로 조회한다. O(1), DB 미접근.

`totalCount` 는 쿠폰 생성 시 한 번 결정되고 이후 변경되지 않으므로 만료 없이 영구 보관한다.

**캐시 미스 시 (Redis 재시작 등)**  
DB에서 읽은 뒤 Redis에 다시 `SET`하고 진행한다. 별도 운영 조치 없이 자가 복구된다.

---

## Step 2 — Redis MULTI/EXEC (원자적 사전 체크)

```java
// CouponIssuedRedisRepository.checkAndRegister()
List<Object> results = redisTemplate.execute(new SessionCallback<>() {
    public List<Object> execute(RedisOperations operations) {
        operations.multi();
        operations.opsForSet().size(key);       // SCARD
        operations.opsForSet().add(key, userId); // SADD
        return operations.exec();                // 원자적 실행
    }
});

Long currentCount = (Long) results.get(0); // SCARD 결과
Long addResult    = (Long) results.get(1); // SADD 결과 (1=신규, 0=중복)
```

### MULTI/EXEC의 원자성

`EXEC` 가 실행되는 동안 다른 클라이언트의 명령이 끼어들 수 없다.  
따라서 SCARD와 SADD 사이에 다른 요청이 끼어드는 일은 없다.

```
Thread A: MULTI → SCARD(99) → SADD(userA) → EXEC  결과: count=100
Thread B:              MULTI → SCARD(100) → SADD(userB) → EXEC  결과: count=101 → 초과 감지
```

Thread B의 SCARD는 Thread A의 EXEC 완료 후 실행되므로 100을 본다.

### SADD 결과가 0이면 중복인 이유

Redis SET은 동일한 값을 허용하지 않는다.  
이미 SET 안에 userId가 있으면 SADD는 값을 추가하지 않고 `0` 을 반환한다.  
DB 조회 없이 중복 발급을 차단한다.

### 분기 처리

| 조건 | 처리 |
|---|---|
| `addResult == 0` | userId가 이미 SET에 존재 → **IsAlreadyIssued (400)** |
| `currentCount >= totalCount` | 수량 소진 → **SREM 후 CouponSoldOutException (409)** |
| 정상 | Step 3 진행 |

수량 초과 시 SREM을 먼저 실행해 SET 상태를 복구한 뒤 예외를 던진다.

---

## Step 3 — DB 발급 (비관적 락)

```java
// @Transactional 범위 안에서 실행
Member member = memberRepository.findById(userId).orElseThrow(...);

Coupon coupon = couponRepository.findByCouponIdForUpdate(couponId).orElseThrow(...);
// → SELECT * FROM coupons WHERE coupon_id = ? FOR UPDATE

coupon.increaseIssueAmount(); // 수량 초과 시 CouponSoldOutException
issueCouponRepository.save(issueCouponRequest.toEntity(member, coupon));
// → INSERT INTO coupon_issues ...
```

### PESSIMISTIC_WRITE 락

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT c FROM Coupon c WHERE c.couponId = :couponId")
Optional<Coupon> findByCouponIdForUpdate(@Param("couponId") Long couponId);
```

`SELECT ... FOR UPDATE` 를 발행해 해당 row에 배타적 잠금을 획득한다.  
잠금을 가진 트랜잭션이 커밋 또는 롤백될 때까지 다른 트랜잭션은 같은 row에서 대기한다.

### increaseIssueAmount — DB 최종 안전장치

```java
// Coupon.java
public void increaseIssueAmount() {
    if (issuedCount >= totalCount) {
        throw new CouponSoldOutException(ErrorCode.COUPON_SOLD_OUT);
    }
    issuedCount++;
}
```

Redis를 통과한 요청이라도 DB에서 한 번 더 수량을 검증한다.  
Redis 데이터 유실 등 극히 드문 불일치 상황에서 초과 발급을 최종 차단한다.

---

## Step 4 — 예외 발생 시 Redis 롤백

```java
try {
    // Step 3 실행
} catch (Exception e) {
    couponIssuedRedisRepository.remove(couponId, userId); // SREM
    throw e;
}
```

DB 발급 중 예외가 발생하면 `coupon:issued:{couponId}` SET에서 userId를 제거한다.  
제거하지 않으면 이후 요청에서 해당 사용자가 영구적으로 `IsAlreadyIssued` 를 받게 된다.

`@Transactional` 이 DB 변경분을 자동 롤백하는 것과는 별개다.  
Redis는 Spring 트랜잭션 관리 대상이 아니므로 catch 블록에서 직접 처리해야 한다.

---

## 동시성 시나리오

### 같은 사용자가 동시에 두 번 요청

```
요청 A: EXEC → [SCARD=0, SADD=1] → 정상 진행 → 발급 완료
요청 B: EXEC → [SCARD=1, SADD=0] → IsAlreadyIssued (400)
```

EXEC는 순서대로 처리된다. 먼저 성공한 SADD가 SET에 값을 넣으면
뒤따르는 SADD는 반드시 0을 반환한다.

### N장 한도에 N+1번째 요청

```
요청 1~N  : SCARD < N, SADD=1 → 정상 발급
요청 N+1  : EXEC → [SCARD=N, SADD=1] → currentCount(N) >= totalCount(N) → SREM + 409
```

### Redis 통과 후 DB에서 품절 감지

Redis 데이터 유실로 Redis 카운트 < 실제 DB 발급 수인 경우.

```
Redis MULTI/EXEC : 통과 (Redis 기준 여유 있음)
DB              : increaseIssueAmount() → issuedCount >= totalCount
                  → CouponSoldOutException
catch           : SREM (Redis 롤백)
@Transactional  : DB 롤백
응답             : 409
```

---

## 트랜잭션 경계

```
IssueCouponService.issueCoupon()  ─┬─ @Transactional 시작
                                    │
  Redis GET  (totalCount 캐시)       │  ← Redis I/O, DB 커넥션 미획득
  Redis MULTI/EXEC                  │  ← Redis I/O, DB 커넥션 미획득
                                    │
  memberRepository.findById()       │  ← DB 커넥션 획득 (첫 번째 DB 접근)
  couponRepository                  │
    .findByCouponIdForUpdate()       │  ← PESSIMISTIC_WRITE 락 획득
  coupon.increaseIssueAmount()      │
  issueCouponRepository.save()      │
                                    │
@Transactional 종료 ───────────────┘  커밋 + 락 해제
```

Redis 조회 구간에서는 DB 커넥션이 열리지 않는다.  
DB 커넥션은 첫 번째 DB 접근(`findById`) 시점에 획득된다.

---

## 쿠폰 생성 / 조회

### 쿠폰 생성

```
POST /api/coupons
  Body: { name, totalCount }

  CouponService.createCoupon()
    [1] couponRepository.save()
          coupons 테이블 INSERT (issuedCount = 0)
    [2] couponIssuedRedisRepository.setTotalCount(couponId, totalCount)
          SET coupon:total:{couponId} {totalCount}   ← 캐시 웜업
    [3] CouponCreateResponse 반환
```

### 쿠폰 조회

```
GET /api/coupons/{couponId}

  CouponService.findCouponById()
    couponRepository.findById(couponId)
    → CouponReadResponse { couponId, name, totalCount, issuedCount }
```
