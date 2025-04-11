package couponToy.CouponToyProject.CouponQueue.service;

import couponToy.CouponToyProject.Coupon.dto.CouponCreateRequest;
import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.Coupon.service.CouponService;
import couponToy.CouponToyProject.CouponIssue.repository.IssueCouponRepository;
import couponToy.CouponToyProject.Member.dto.MemberSignUpRequest;
import couponToy.CouponToyProject.Member.dto.MemberSignUpResponse;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.Member.service.MemberService;
import couponToy.CouponToyProject.global.security.MemberDetails;
import couponToy.CouponToyProject.queue.repository.CouponQueueRepository;
import couponToy.CouponToyProject.queue.service.CouponQueueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class CouponQueueServiceTest {

    @Autowired
    private CouponQueueService couponQueueService;

    @Autowired
    private CouponQueueRepository couponQueueRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponService couponService;

    @Autowired
    private IssueCouponRepository issueCouponRepository;

    @Autowired
    private DataSource dataSource;


    private Coupon createTestCoupon(int totalCount) {
        CouponCreateRequest request = new CouponCreateRequest("RedisTestCoupon", totalCount);
        return couponRepository.findById(couponService.createCoupon(request).getCouponId())
                .orElseThrow();
    }

    private Member createTestMember() {
        MemberSignUpRequest signUpRequest = MemberSignUpRequest.builder()
                .email("test@example.com")
                .password("test1234!@")
                .name("레디스테스트유저")
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
                .name("대기열테스트유저" + index)
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

    private void clearRedis(Long couponId) {
        redisTemplate.delete("coupon:waiting:" + couponId);
        redisTemplate.delete("coupon:issued:" + couponId);
    }

    @AfterEach
    void clearRedisAll() {
        redisTemplate.delete(redisTemplate.keys("coupon:*"));
    }

//    @AfterEach
//    void resetDatabase() throws Exception {
//        try (Connection conn = dataSource.getConnection();
//             Statement stmt = conn.createStatement()) {
//
//            stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
//            stmt.execute("TRUNCATE TABLE coupon_issues");
//            stmt.execute("TRUNCATE TABLE coupons");
//            stmt.execute("TRUNCATE TABLE members");
//            stmt.execute("ALTER TABLE coupon_issues AUTO_INCREMENT = 1");
//            stmt.execute("ALTER TABLE coupons AUTO_INCREMENT = 1");
//            stmt.execute("ALTER TABLE members AUTO_INCREMENT = 1");
//            stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
//        }
//    }

    @Test
    @Transactional
    @DisplayName("사용자 Redis 대기열 등록 및 순번 확인")
    void addToQueueAndCheckRank() {
        // given
        Coupon coupon = createTestCoupon(30);
        Member member = createTestMember();

        Long couponId = coupon.getCouponId();
        Long userId = member.getMemberId();
        clearRedis(couponId);

        couponQueueService.registerUser(couponId, userId);
        Long rank = couponQueueService.getUserRank(couponId, userId);

        System.out.println("couponId = " + couponId);
        System.out.println("userId = " + userId);
        System.out.println("rank = " + rank);

        // then
        assertThat(rank).isEqualTo(0L);

    }

    @Test
    @Transactional
    @DisplayName("발급 완료된 사용자는 대기열에 등록되지 않음")
    void alreadyIssuedUserIsSkipped() {
        // given
        Coupon coupon = createTestCoupon(30);
        Member member = createTestMember();

        Long couponId = coupon.getCouponId();
        Long userId = member.getMemberId();
        clearRedis(couponId);

        couponQueueRepository.addToIssuedSet(couponId, userId);

        // when
        couponQueueService.registerUser(couponId, userId);
        Long rank = couponQueueService.getUserRank(couponId, userId);

        System.out.println("couponId = " + couponId);
        System.out.println("userId = " + userId);
        System.out.println("rank = " + rank);

        // then
        assertThat(rank).isNull();
    }

    @Test
    @DisplayName("대기열 등록 후 대기열 사이즈 확인")
    @Transactional
    void checkQueueSize() {

        // given
        Coupon coupon = createTestCoupon(30);
        Long couponId = coupon.getCouponId();
        clearRedis(couponId);

        for (int i = 0; i < 3; i++) {
            Member member = createMultiTestMember(i);
            Long userId = member.getMemberId();

            couponQueueService.registerUser(couponId, userId);
        }

        Long size = couponQueueService.getQueueSize(couponId);

        System.out.println("size = " + size);

        // then
        assertThat(size).isEqualTo(3L);
    }

    @Test
    @DisplayName("멀티 스레드 환경에서 대기열 등록 테스트")
    void concurrentQueueRegistrationTest() throws InterruptedException {

        // given
        int threadCount = 50;
        int couponAmount = 10;

        Coupon coupon = createTestCoupon(couponAmount);
        Long couponId = coupon.getCouponId();
        clearRedis(couponId);

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
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
                    couponQueueService.registerUser(couponId, member.getMemberId());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.out.println("발급 실패: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(); // 모든 스레드 작업 종료 대기

        // then
        Long queueSize = couponQueueService.getQueueSize(couponId);
        System.out.println("successCount: " + successCount.get());
        System.out.println("failCount: " + failCount.get());
        System.out.println("queueSize: " + queueSize);

        assertThat(queueSize).isEqualTo(threadCount);
    }


    @Test
    @DisplayName("대기열 등록 후 스케줄러 발급 정상 처리")
    void schedulerIssueFlowTest() throws InterruptedException {

        // given
        int userCount = 50;
        int couponAmount = 10;

        Coupon coupon = createTestCoupon(couponAmount);
        Long couponId = coupon.getCouponId();
        clearRedis(couponId);

        for (int i = 0; i < userCount; i++) {
            Member member = createMultiTestMember(i);
            couponQueueService.registerUser(couponId, member.getMemberId());
        }

        // when
        Thread.sleep(3000);

        // then
        long issuedCount = issueCouponRepository.countByCouponId(couponId);
        Long redisIssuedSize = redisTemplate.opsForZSet().size("coupon:issued:" + couponId);

        System.out.println("쿠폰 발급 수량 (DB): " + issuedCount);
        System.out.println("쿠폰 발급 수량 (Redis): " + redisIssuedSize);

        assertThat(issuedCount).isEqualTo(couponAmount);
        assertThat(redisIssuedSize).isEqualTo((long) couponAmount);
    }


    @Test
    @DisplayName("멀티 스레드 환경 쿠폰 발급 테스트")
    void concurrentQueueIssueTest() throws InterruptedException {

        // given
        int threadCount = 100;
        int couponAmount = 70;

        Coupon coupon = createTestCoupon(couponAmount);
        Long couponId = coupon.getCouponId();
        clearRedis(couponId);

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
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
                    couponQueueService.registerUser(couponId, member.getMemberId());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.out.println("발급 실패: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        Thread.sleep(10000);
        latch.await(); // 모든 스레드 작업 종료 대기

        // then
        long issuedCount = issueCouponRepository.countByCouponId(couponId);
        Long redisIssuedSize = redisTemplate.opsForZSet().size("coupon:issued:" + couponId);

        System.out.println("쿠폰 발급 수량 (DB): " + issuedCount);
        System.out.println("쿠폰 발급 수량 (Redis): " + redisIssuedSize);

        assertThat(issuedCount).isEqualTo(couponAmount);
        assertThat(redisIssuedSize).isEqualTo((long) couponAmount);
    }


    @Test
    @DisplayName("목업 유저 생성")
    void makeMultiUser() throws InterruptedException {

        // given
        int userCount = 1000;

        for (int i = 0; i < userCount; i++) {
            Member member = createMultiTestMember(i);
        }
    }
}
