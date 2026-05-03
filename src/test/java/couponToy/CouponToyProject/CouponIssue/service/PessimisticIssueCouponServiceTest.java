package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.support.AbstractCouponIssueStrategyIntegrationTest;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.global.security.MemberDetails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PessimisticIssueCouponServiceTest extends AbstractCouponIssueStrategyIntegrationTest {

    @Autowired
    private PessimisticIssueCouponService pessimisticIssueCouponService;

    @Override
    protected IssueCouponResponse issue(Member member, Long couponId) {
        return pessimisticIssueCouponService.issueCoupon(MemberDetails.create(member), couponId);
    }
}
