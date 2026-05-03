package couponToy.CouponToyProject.CouponIssue.controller;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.CouponIssue.support.AbstractCouponIssueIntegrationTestSupport;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.global.security.MemberDetails;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IssueCouponControllerIntegrationTest extends AbstractCouponIssueIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Redis 발급 endpoint 성공 응답 포맷")
    void redisIssueEndpointSuccessContract() throws Exception {
        Coupon coupon = createCoupon(10);
        Member member = createMember(0);

        performIssue("/coupons/issue/{couponId}", member, coupon.getCouponId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.response.memberId").value(member.getMemberId()))
                .andExpect(jsonPath("$.response.couponId").value(coupon.getCouponId()))
                .andExpect(jsonPath("$.error").value(Matchers.nullValue()));
    }

    @Test
    @DisplayName("비관적 락 endpoint 성공 응답 포맷")
    void pessimisticIssueEndpointSuccessContract() throws Exception {
        Coupon coupon = createCoupon(10);
        Member member = createMember(1);

        performIssue("/coupons/issue/pessimistic/{couponId}", member, coupon.getCouponId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.response.memberId").value(member.getMemberId()))
                .andExpect(jsonPath("$.response.couponId").value(coupon.getCouponId()))
                .andExpect(jsonPath("$.error").value(Matchers.nullValue()));
    }

    @Test
    @DisplayName("두 endpoint 모두 중복 발급 시 같은 409 응답 포맷")
    void duplicateIssueContractMatches() throws Exception {
        assertDuplicateContract("/coupons/issue/{couponId}");
        assertDuplicateContract("/coupons/issue/pessimistic/{couponId}");
    }

    @Test
    @DisplayName("두 endpoint 모두 품절 시 같은 409 응답 포맷")
    void soldOutContractMatches() throws Exception {
        assertSoldOutContract("/coupons/issue/{couponId}");
        assertSoldOutContract("/coupons/issue/pessimistic/{couponId}");
    }

    private void assertDuplicateContract(String pathTemplate) throws Exception {
        Coupon coupon = createCoupon(10);
        Member member = createMember(pathTemplate.contains("pessimistic") ? 20 : 10);

        performIssue(pathTemplate, member, coupon.getCouponId())
                .andExpect(status().isCreated());

        performIssue(pathTemplate, member, coupon.getCouponId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.response").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.error.message").value("이미 쿠폰 발급 받은 사용자입니다."))
                .andExpect(jsonPath("$.error.status").value(409));
    }

    private void assertSoldOutContract(String pathTemplate) throws Exception {
        Coupon coupon = createCoupon(1);
        Member firstMember = createMember(pathTemplate.contains("pessimistic") ? 40 : 30);
        Member secondMember = createMember(pathTemplate.contains("pessimistic") ? 41 : 31);

        performIssue(pathTemplate, firstMember, coupon.getCouponId())
                .andExpect(status().isCreated());

        performIssue(pathTemplate, secondMember, coupon.getCouponId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.response").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.error.message").value("쿠폰이 모두 소진되었습니다."))
                .andExpect(jsonPath("$.error.status").value(409));
    }

    private ResultActions performIssue(String pathTemplate, Member member, Long couponId) throws Exception {
        return mockMvc.perform(
                post(pathTemplate, couponId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(authentication(buildAuthentication(member)))
        );
    }

    private Authentication buildAuthentication(Member member) {
        MemberDetails memberDetails = MemberDetails.create(member);
        return new UsernamePasswordAuthenticationToken(
                memberDetails,
                null,
                memberDetails.getAuthorities()
        );
    }
}
