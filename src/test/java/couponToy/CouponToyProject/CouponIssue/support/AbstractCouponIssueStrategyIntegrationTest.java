package couponToy.CouponToyProject.CouponIssue.support;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import couponToy.CouponToyProject.global.exception.IsAlreadyIssued;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class AbstractCouponIssueStrategyIntegrationTest extends AbstractCouponIssueIntegrationTestSupport {

    protected abstract IssueCouponResponse issue(Member member, Long couponId);

    @Test
    @DisplayName("쿠폰 1회 발급 성공")
    void issueCouponOnce() {
        Coupon coupon = createCoupon(10);
        Member member = createMember(0);

        IssueCouponResponse response = issue(member, coupon.getCouponId());

        assertThat(response.getCouponId()).isEqualTo(coupon.getCouponId());
        assertThat(response.getMemberId()).isEqualTo(member.getMemberId());
        assertThat(issueCouponRepository.countByCouponId(coupon.getCouponId())).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 사용자의 중복 발급 방지")
    void preventDuplicateIssue() {
        Coupon coupon = createCoupon(10);
        Member member = createMember(1);

        issue(member, coupon.getCouponId());

        assertThatThrownBy(() -> issue(member, coupon.getCouponId()))
                .isInstanceOf(IsAlreadyIssued.class);
        assertThat(issueCouponRepository.countByCouponId(coupon.getCouponId())).isEqualTo(1);
    }

    @Test
    @DisplayName("여러 사용자 순차 발급 시 발급량 상한 보장")
    void sequentialIssueRespectsLimit() {
        int memberCount = 10;
        int couponAmount = 5;

        Coupon coupon = createCoupon(couponAmount);
        List<Member> members = IntStream.range(0, memberCount)
                .mapToObj(this::createMember)
                .toList();

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger soldOutCount = new AtomicInteger();
        AtomicInteger duplicateCount = new AtomicInteger();

        for (Member member : members) {
            try {
                issue(member, coupon.getCouponId());
                successCount.incrementAndGet();
            } catch (CouponSoldOutException ex) {
                soldOutCount.incrementAndGet();
            } catch (IsAlreadyIssued ex) {
                duplicateCount.incrementAndGet();
            }
        }

        assertThat(issueCouponRepository.countByCouponId(coupon.getCouponId())).isEqualTo(couponAmount);
        assertThat(successCount.get()).isEqualTo(couponAmount);
        assertThat(soldOutCount.get()).isEqualTo(memberCount - couponAmount);
        assertThat(duplicateCount.get()).isZero();
    }

    @Test
    @DisplayName("여러 사용자 동시 발급 시 발급량 상한 보장")
    void concurrentIssueRespectsLimit() throws InterruptedException {
        int threadCount = 30;
        int couponAmount = 10;

        Coupon coupon = createCoupon(couponAmount);
        List<Member> members = IntStream.range(0, threadCount)
                .mapToObj(index -> createMember(index + 100))
                .toList();

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger soldOutCount = new AtomicInteger();
        AtomicInteger duplicateCount = new AtomicInteger();
        AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();

        for (Member member : members) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    issue(member, coupon.getCouponId());
                    successCount.incrementAndGet();
                } catch (CouponSoldOutException ex) {
                    soldOutCount.incrementAndGet();
                } catch (IsAlreadyIssued ex) {
                    duplicateCount.incrementAndGet();
                } catch (Throwable ex) {
                    unexpectedFailure.compareAndSet(null, ex);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();

        assertThat(unexpectedFailure.get()).isNull();
        assertThat(issueCouponRepository.countByCouponId(coupon.getCouponId())).isEqualTo(couponAmount);
        assertThat(successCount.get()).isEqualTo(couponAmount);
        assertThat(soldOutCount.get()).isEqualTo(threadCount - couponAmount);
        assertThat(duplicateCount.get()).isZero();
    }

}
