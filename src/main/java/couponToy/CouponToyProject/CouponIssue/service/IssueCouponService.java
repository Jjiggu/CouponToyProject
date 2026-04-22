package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponRequest;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.model.IssueCoupon;
import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.CouponIssue.repository.IssueCouponRepository;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import couponToy.CouponToyProject.global.security.MemberDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IssueCouponService {

    private final IssueCouponRepository issueCouponRepository;
    private final MemberRepository memberRepository;
    private final CouponRepository couponRepository;
    private final CouponIssuedRedisRepository couponIssuedRedisRepository;

    @Transactional
    public IssueCouponResponse issueCoupon(IssueCouponRequest issueCouponRequest, MemberDetails memberDetails, Long couponId) {
        Long userId = memberDetails.getMemberId();

        // [1] totalCount 조회: Redis 캐시 우선, 캐시 미스 시 DB 폴백 후 재캐싱
        int totalCount = couponIssuedRedisRepository.getTotalCount(couponId)
                .orElseGet(() -> {
                    Coupon c = couponRepository.findById(couponId)
                            .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON));
                    couponIssuedRedisRepository.setTotalCount(couponId, c.getTotalCount());
                    return c.getTotalCount();
                });

        // [2] Redis MULTI/EXEC: 중복 방지 + 수량 사전 체크
        couponIssuedRedisRepository.checkAndRegister(couponId, userId, totalCount);

        // [3] 비관적 락으로 DB 발급 처리 (최종 안전장치)
        try {
            Member member = memberRepository.findById(userId)
                    .orElseThrow(() -> new MemberNotFoundException(ErrorCode.NOT_FOUND_MEMBER));

            Coupon coupon = couponRepository.findByCouponIdForUpdate(couponId)
                    .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON));

            coupon.increaseIssueAmount();
            IssueCoupon issueCoupon = issueCouponRepository.save(issueCouponRequest.toEntity(member, coupon));

            return IssueCouponResponse.fromEntity(issueCoupon);

        } catch (Exception e) {
            // DB 발급 실패 시 Redis 상태 롤백
            couponIssuedRedisRepository.remove(couponId, userId);
            throw e;
        }
    }
}

