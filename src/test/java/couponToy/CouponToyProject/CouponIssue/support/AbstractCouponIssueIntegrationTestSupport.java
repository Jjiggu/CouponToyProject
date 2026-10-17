package couponToy.CouponToyProject.CouponIssue.support;

import couponToy.CouponToyProject.Coupon.dto.CouponCreateRequest;
import couponToy.CouponToyProject.Coupon.dto.CouponCreateResponse;
import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.Coupon.service.CouponService;
import couponToy.CouponToyProject.CouponIssue.repository.IssueCouponRepository;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.global.constant.Role;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;
import java.util.UUID;

// 테스트는 Redis db 1 을 쓴다. 로컬 실험 데이터(db 0)를 cleanup 이 지우지 않게 하기 위해서다.
@ActiveProfiles("local")
@TestPropertySource(properties = "spring.data.redis.database=1")
public abstract class AbstractCouponIssueIntegrationTestSupport {

    @Autowired
    protected CouponService couponService;

    @Autowired
    protected CouponRepository couponRepository;

    @Autowired
    protected IssueCouponRepository issueCouponRepository;

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
        return memberRepository.save(Member.builder()
                .email("perf-" + index + "-" + UUID.randomUUID() + "@example.com")
                .password("-")
                .name("perf" + index)
                .role(Role.MEMBER)
                .build());
    }

    private void clearRedisKeys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
