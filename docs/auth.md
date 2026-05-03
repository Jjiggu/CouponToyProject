# 인증 플로우

Spring Security + JWT 기반 Stateless 인증 구조.  
세션을 사용하지 않으며, 모든 인증 상태는 JWT 토큰에 담긴다.

---

## 1. 회원가입

### 흐름

```
POST /members/signup
  Body: { email, password, name }

  MemberController
    └─ MemberService.signUpMember()
         [1] memberRepository.findByEmail(email)
               이미 존재하면 → DuplicateMemberException (400)
         [2] BCryptPasswordEncoder.encode(password)
               평문 비밀번호를 해시로 변환
         [3] memberRepository.save(member)
               members 테이블 INSERT, role = MEMBER 고정
         [4] MemberSignUpResponse 반환
```

### 요청 / 응답

**요청**
```json
{
  "email": "user@example.com",
  "password": "test1234!@",
  "name": "홍길동"
}
```

**성공 (201)**
```json
{
  "success": true,
  "response": {
    "memberId": 1,
    "email": "user@example.com",
    "name": "홍길동",
    "role": "MEMBER"
  },
  "error": null
}
```

**실패 (400) — 이메일 중복**
```json
{
  "success": false,
  "response": null,
  "error": { "message": "이미 존재하는 이메일입니다", "status": 400 }
}
```

---

## 2. 로그인

### 흐름

```
POST /members/login
  Body: { email, password }

  MemberController
    └─ MemberService.login()
         [1] DaoAuthenticationProvider.authenticate(email, password)
               └─ MemberDetailService.loadUserByUsername(email)
                    memberRepository.findByEmail(email)
               └─ BCryptPasswordEncoder.matches(입력PW, 저장해시)
               실패 → AuthenticationException → 401
         [2] JwtService.issue(memberId, email, role)
               알고리즘: HMAC256(jwt.secret-key)
               만료:     발급 시각 + 7일
               클레임:   sub=memberId, email, roles=["MEMBER"]
         [3] MemberLogInResponse 반환 { memberId, email, name, accessToken }
```

### 요청 / 응답

**요청**
```json
{
  "email": "user@example.com",
  "password": "test1234!@"
}
```

**성공 (200)**
```json
{
  "success": true,
  "response": {
    "memberId": 1,
    "email": "user@example.com",
    "name": "홍길동",
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  },
  "error": null
}
```

**실패 (401)**
```json
{
  "success": false,
  "response": null,
  "error": { "message": "인증 오류", "status": 401 }
}
```

---

## 3. 인증이 필요한 요청

로그인 이후의 모든 요청에는 `Authorization` 헤더를 포함해야 한다.

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

### JWT 검증 흐름 (JwtAuthenticationFilter)

`OncePerRequestFilter`로 등록되어 모든 요청에서 한 번 실행된다.

```
HTTP Request
  │
  └─ JwtAuthenticationFilter.doFilterInternal()
       [1] Authorization 헤더에서 "Bearer " 제거 후 토큰 추출
             헤더 없음 → 토큰 없이 다음 필터로 통과
                         (permitAll 경로면 그대로 처리,
                          보호 경로면 이후 401 반환)
       [2] JwtService.decode(token)
             HMAC256 서명 검증
             위변조 토큰 → 예외 catch → 로그 기록 후 다음 필터 통과
       [3] JwtService.isExpired(decodedJWT)
             만료 토큰 → 인증 정보 미설정 → 다음 필터 통과 → 이후 401
       [4] MemberDetailService.loadUserById(memberId)
             memberRepository.findById(memberId)로 DB에서 회원 조회
       [5] UsernamePasswordAuthenticationToken 생성
             SecurityContextHolder에 저장
       [6] 다음 필터 전달
  │
  └─ Controller
       @AuthenticationPrincipal MemberDetails
         → memberDetails.getMemberId() 로 userId 추출
```

### JWT 클레임 구조

| 클레임 | 값 | 설명 |
|---|---|---|
| `sub` | "1" | 회원 PK (String) |
| `email` | "user@example.com" | 이메일 |
| `roles` | ["MEMBER"] | 권한 목록 |
| `exp` | Unix timestamp | 만료 시각 (발급 + 7일) |

---

## 4. Security 설정 요약

```
Session 정책  : STATELESS  (서버 세션 없음)
CSRF          : disabled
Form login    : disabled
```

| 경로 | 인증 |
|---|---|
| `POST /members/signup` | 불필요 (permitAll) |
| `POST /members/login` | 불필요 (permitAll) |
| 그 외 모든 경로 | 필요 (authenticated) |

인증 실패 시 `UnauthorizedHandler`가 `ApiResult` 형태의 401 응답을 반환한다.
