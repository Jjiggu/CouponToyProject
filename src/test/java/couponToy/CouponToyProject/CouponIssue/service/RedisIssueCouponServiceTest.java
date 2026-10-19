package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.support.AbstractCouponIssueStrategyIntegrationTest;
import couponToy.CouponToyProject.Member.model.Member;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RedisIssueCouponServiceTest extends AbstractCouponIssueStrategyIntegrationTest {

    @Autowired
    private RedisIssueCouponService redisIssueCouponService;

    @Override
    protected IssueCouponResponse issue(Member member, Long couponId) {
        return redisIssueCouponService.issue(member.getMemberId(), couponId, RedisIssueCouponService.ReservationMethod.LUA, attempt -> {
        });
    }
}
