package couponToy.CouponToyProject.WriteBehind.service;

import couponToy.CouponToyProject.CouponIssue.service.RedisIssueCouponService;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 9단계 write-behind 의 요청 쪽. Redis 가 재고와 발급 회원 명단의 기준이고, DB 는 적재기(WriteBehindLoader)가 나중에 채운다.
 * Lua 한 번으로 중복·재고를 판단하고, 예약(SADD)과 적재 요청 기록(XADD)을 함께 남긴다. 요청 쪽은 DB 에 쓰지 않는다.
 *
 * 전체 흐름. 세 곳이 Redis 를 사이에 두고 따로 움직인다.
 *   [요청] 이 클래스 (local 프로필, API 프로세스)
 *          회원 확인 → Lua: 중복? → 재고? → XADD coupon:wb:stream → SADD coupon:issued:{id} → 202 (QUEUED)
 *   [적재] WriteBehindLoader.loadOnce() (wb-loader 프로필, 따로 띄운 프로세스의 스레드 하나, WriteBehindLoaderConfig)
 *          스트림에서 최대 100건 읽기 → 한 트랜잭션에 INSERT → 커밋 → XACK
 *   [대사] WriteBehindLoader.reconcile() (POST /lab/write-behind/coupons/{id}/reconcile)
 *          Redis 명단과 DB 행 비교 → Redis 에만 있는 회원 INSERT, issued_count 를 행 수로 맞춤
 *
 * 왜 이렇게 나눴나: 8단계까지는 요청마다 DB 에 INSERT 와 쿠폰 행 UPDATE 를 했고, 모든 확정이 쿠폰 행 하나의 락을 쥔 채
 * 커밋을 기다리느라 막혔다. 여기서는 요청이 Redis 에만 쓰고 바로 응답하고, DB 는 적재기가 여러 건씩 묶어 INSERT 만 한다.
 * 대가로 초과 발급을 막는 것은 Redis 의 Lua 판단 하나뿐이다(DB 의 조건부 UPDATE 방어가 없다). 측정은 docs/learning/09-write-behind.md
 */
@Service
@RequiredArgsConstructor
public class WriteBehindIssueService {

    // 적재 요청을 쌓는 Redis Stream 키. 6단계(coupon:issue:stream)와 다른 키라 두 단계의 소비자가 서로의 메시지를 가져가지 않는다
    public static final String STREAM = "coupon:wb:stream";
    // 소비자 그룹 이름. 그룹은 "어디까지 나눠 줬는지" 와 "나눠 줬지만 아직 ACK 를 못 받은 메시지(pending)" 를 기억한다(WriteBehindLoader 설명 참고)
    public static final String GROUP = "coupon-wb-loaders";

    private final MemberRepository memberRepository;
    private final RedisIssueCouponService redisIssueCouponService;

    public void issue(Long memberId, Long couponId) {
        // 적재기는 확인 없이 INSERT 만 하므로, 없는 회원은 여기서 막는다(기본 키 조회, 쓰기 아님)
        // coupon_issues 에는 회원 외래 키가 없어서, 여기서 막지 않으면 없는 회원 번호도 그대로 적재된다.
        // 요청마다 DB 에 가는 일은 이것 하나뿐이고, 처리량을 올렸을 때 다음 병목이 이 조회였다(문서 4절)
        if (!memberRepository.existsById(memberId)) {
            throw new MemberNotFoundException(ErrorCode.NOT_FOUND_MEMBER);
        }
        // Lua 스크립트 한 번으로 중복 → 재고 → XADD → SADD. 중복이면 IsAlreadyIssued, 품절이면 CouponSoldOutException(둘 다 409).
        // 예외 없이 돌아오면 Redis 에 예약과 적재 요청이 함께 남은 것이고, LabService 가 202(QUEUED)로 응답한다. DB 에는 아직 없다
        redisIssueCouponService.reserveAndEnqueue(memberId, couponId, STREAM);
    }
}
