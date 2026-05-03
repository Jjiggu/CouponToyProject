package couponToy.CouponToyProject.CouponIssue.support;

import couponToy.CouponToyProject.Coupon.dto.CouponCreateRequest;
import couponToy.CouponToyProject.Coupon.dto.CouponCreateResponse;
import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.Coupon.service.CouponService;
import couponToy.CouponToyProject.CouponIssue.repository.IssueCouponRepository;
import couponToy.CouponToyProject.Member.dto.MemberSignUpRequest;
import couponToy.CouponToyProject.Member.dto.MemberSignUpResponse;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.Member.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.UUID;

public abstract class AbstractCouponIssueIntegrationTestSupport {

    @Autowired
    protected CouponService couponService;

    @Autowired
    protected CouponRepository couponRepository;

    @Autowired
    protected IssueCouponRepository issueCouponRepository;

    @Autowired
    protected MemberService memberService;

    @Autowired
    protected MemberRepository memberRepository;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanupData() {
        clearRedisKeys("coupon:issued:*");
        clearRedisKeys("coupon:total:*");
        issueCouponRepository.deleteAllInBatch();
        couponRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    protected Coupon createCoupon(int totalCount) {
        CouponCreateRequest request = new CouponCreateRequest("성능테스트쿠폰", totalCount);
        CouponCreateResponse response = couponService.createCoupon(request);

        return couponRepository.findById(response.getCouponId()).orElseThrow();
    }

    protected Member createMember(int index) {
        MemberSignUpRequest request = MemberSignUpRequest.builder()
                .email("perf-" + index + "-" + UUID.randomUUID() + "@example.com")
                .password("Passw0rd!")
                .name(buildAlphabeticName(index))
                .build();

        MemberSignUpResponse response = memberService.signUpMember(request);
        return memberRepository.findById(response.getMemberId()).orElseThrow();
    }

    private void clearRedisKeys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private String buildAlphabeticName(int index) {
        StringBuilder suffix = new StringBuilder();
        int value = index;

        do {
            suffix.append((char) ('a' + (value % 26)));
            value = value / 26 - 1;
        } while (value >= 0);

        return "Perf" + suffix.reverse();
    }
}
