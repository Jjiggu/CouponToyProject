package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.Coupon.dto.CouponCreateRequest;
import couponToy.CouponToyProject.Coupon.dto.CouponCreateResponse;
import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.Coupon.service.CouponService;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponRequest;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.repository.IssueCouponRepository;
import couponToy.CouponToyProject.Member.dto.MemberSignUpRequest;
import couponToy.CouponToyProject.Member.dto.MemberSignUpResponse;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.Member.service.MemberService;
import couponToy.CouponToyProject.global.security.MemberDetails;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
@Transactional
public class IssueCouponServiceTest {

    @Autowired
    private IssueCouponService issueCouponService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponService couponService;

    @Autowired
    private IssueCouponRepository issueCouponRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberService memberService;

    /**
     *  회원 생성 및 SecurityContext 설정
     */
    private Member createTestMember() {
        MemberSignUpRequest signUpRequest = MemberSignUpRequest.builder()
                .email("test@example.com")
                .password("test1234!@")
                .name("발급테스트유저")
                .build();

        MemberSignUpResponse createdMember = memberService.signUpMember(signUpRequest);
        Member member = memberRepository.findById(createdMember.getMemberId()).orElseThrow();

        // Security Context 설정
        MemberDetails memberDetails = MemberDetails.create(member);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                memberDetails, null, memberDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        return member;
    }

    /**
     *  쿠폰 생성 메서드
     */
    private Coupon createTestCoupon(int totalCount) {
        CouponCreateRequest couponCreateRequest = new CouponCreateRequest("테스트 쿠폰", totalCount);
        CouponCreateResponse createdCoupon = couponService.createCoupon(couponCreateRequest);
        return couponRepository.findById(createdCoupon.getCouponId()).orElseThrow();
    }

    @Test
    @DisplayName("쿠폰 발급 테스트")
    void issuedCouponTest() {
        // given
        Coupon coupon = createTestCoupon(10);
        Member member = createTestMember();
        IssueCouponRequest request = new IssueCouponRequest(member, coupon);

        // when
        IssueCouponResponse issueCoupon = issueCouponService.issueCoupon(request,
                (MemberDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal(),
                coupon.getCouponId());

        // then
        assertThat(issueCoupon).isNotNull();
        assertThat(issueCoupon.getCouponId()).isEqualTo(coupon.getCouponId());
        assertThat(issueCoupon.getMemberId()).isEqualTo(member.getMemberId());
    }

    @Test
    @DisplayName("쿠폰 순차적 발급 테스트")
    void issuedConcurrencyTest() {
        // given
        int memberCount = 30;
        int couponAmount = 30;

        Coupon coupon = createTestCoupon(couponAmount);
        Member member = createTestMember();
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when
        for (int i = 0; i < memberCount; i++) {
            try {
                IssueCouponRequest request = new IssueCouponRequest(member, coupon);
                issueCouponService.issueCoupon(request,
                        (MemberDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal(),
                        coupon.getCouponId());
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            }
        }

        System.out.println("successCount = " + successCount);
        System.out.println("failCount = " + failCount);

        // then
        long issuedCoupons = issueCouponRepository.countByCoupon_CouponId(coupon.getCouponId());
        assertThat(issuedCoupons).isEqualTo(Math.min(memberCount, couponAmount));
    }
}
