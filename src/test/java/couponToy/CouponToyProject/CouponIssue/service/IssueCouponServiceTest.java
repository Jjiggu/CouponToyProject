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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
//@Transactional
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

    private Member createMultiTestMember(int index) {
        MemberSignUpRequest signUpRequest = MemberSignUpRequest.builder()
                .email("test" + index + "@example.com") // 인덱스로 유니크 처리
                .password("test1234!@")
                .name("발급테스트유저" + index)
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
    @DisplayName("쿠폰 생성 테스트")
    void issuedCouponTest() {
        // given
        Coupon coupon = createTestCoupon(10);
        Member member = createTestMember();
        IssueCouponRequest request = new IssueCouponRequest(member.getMemberId(), coupon.getCouponId());

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
        int memberCount = 10;
        int couponAmount = 5;

        Coupon coupon = createTestCoupon(couponAmount);
        Member member = createTestMember();
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when
        for (int i = 0; i < memberCount; i++) {
            try {
                IssueCouponRequest request = new IssueCouponRequest(member.getMemberId(), coupon.getCouponId());
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
//        long issuedCoupons = issueCouponRepository.countByCoupon_CouponId(coupon.getCouponId());
        long issuedCoupons = issueCouponRepository.countByCouponId(coupon.getCouponId());
        assertThat(issuedCoupons).isEqualTo(Math.min(memberCount, couponAmount));
    }


    @Test
    @DisplayName("서로 다른 30명의 사용자가 동시에 10장 쿠폰 발급 시도 실패")
    void issuedConcurrencyTest_concurrent() throws InterruptedException {
        // given
        int threadCount = 30;
        int couponAmount = 10;

        Coupon coupon = createTestCoupon(couponAmount);
        System.out.println("🎫 생성된 쿠폰 ID: " + coupon.getCouponId());


        ExecutorService executorService = Executors.newFixedThreadPool(30);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when
        for (int i = 0; i < threadCount; i++) {
            int index = i;
            executorService.submit(() -> {
                try {
                    // 1. 회원 개별 생성
                    Member member = createMultiTestMember(index);

                    // 3. 쿠폰 발급 시도
                    IssueCouponRequest request = new IssueCouponRequest(member.getMemberId(), coupon.getCouponId());
                    issueCouponService.issueCoupon(request,
                            (MemberDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal(),
                            coupon.getCouponId());

                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.out.println("❌ 발급 실패: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(); // 모든 스레드 작업 종료 대기

        // then
//        long issuedCoupons = issueCouponRepository.countByCoupon_CouponId(coupon.getCouponId());
        long issuedCoupons = issueCouponRepository.countByCouponId(coupon.getCouponId());

        System.out.println("✅ 성공 수: " + successCount.get());
        System.out.println("❌ 실패 수: " + failCount.get());
        System.out.println("📦 DB 발급 수: " + issuedCoupons);

        assertThat(issuedCoupons).isNotEqualTo(Math.min(threadCount, couponAmount));
    }
}
