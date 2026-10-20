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
 */
@Service
@RequiredArgsConstructor
public class WriteBehindIssueService {

    public static final String STREAM = "coupon:wb:stream";
    public static final String GROUP = "coupon-wb-loaders";

    private final MemberRepository memberRepository;
    private final RedisIssueCouponService redisIssueCouponService;

    public void issue(Long memberId, Long couponId) {
        // 적재기는 확인 없이 INSERT 만 하므로, 없는 회원은 여기서 막는다(기본 키 조회, 쓰기 아님)
        if (!memberRepository.existsById(memberId)) {
            throw new MemberNotFoundException(ErrorCode.NOT_FOUND_MEMBER);
        }
        redisIssueCouponService.reserveAndEnqueue(memberId, couponId, STREAM);
    }
}
