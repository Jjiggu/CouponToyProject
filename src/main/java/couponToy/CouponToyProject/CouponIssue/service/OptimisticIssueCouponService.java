package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.Coupon.model.VersionedCoupon;
import couponToy.CouponToyProject.Coupon.repository.VersionedCouponRepository;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.model.IssueCoupon;
import couponToy.CouponToyProject.CouponIssue.repository.IssueCouponRepository;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.exception.IsAlreadyIssued;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntConsumer;

/**
 * 2단계: 낙관적 락. 1단계·비관적 락과 같은 순서(회원 → 쿠폰 → 중복 확인 → 증가 → 저장)로 발급하되,
 * 쿠폰은 락 없이 읽고 커밋 시 version 으로 충돌을 검사한다. 격리 수준은 MySQL 기본값(REPEATABLE READ).
 *
 * 트랜잭션 경계를 TransactionTemplate 으로 직접 연다. 재시도마다 새 트랜잭션이 열리는 것이 코드에 보이도록 하기 위해서다.
 */
@Service
public class OptimisticIssueCouponService {

    private final IssueCouponRepository issueCouponRepository;
    private final MemberRepository memberRepository;
    private final VersionedCouponRepository versionedCouponRepository;
    private final TransactionTemplate transactionTemplate;
    private final int maxAttempts;
    private final long backoffBaseMs;
    private final long backoffMaxMs;

    public OptimisticIssueCouponService(
            IssueCouponRepository issueCouponRepository,
            MemberRepository memberRepository,
            VersionedCouponRepository versionedCouponRepository,
            PlatformTransactionManager transactionManager,
            @Value("${coupon.issue.optimistic.max-attempts:5}") int maxAttempts,
            @Value("${coupon.issue.optimistic.backoff-base-ms:10}") long backoffBaseMs,
            @Value("${coupon.issue.optimistic.backoff-max-ms:100}") long backoffMaxMs
    ) {
        this.issueCouponRepository = issueCouponRepository;
        this.memberRepository = memberRepository;
        this.versionedCouponRepository = versionedCouponRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxAttempts = maxAttempts;
        this.backoffBaseMs = backoffBaseMs;
        this.backoffMaxMs = backoffMaxMs;
    }

    /**
     * 재시도 없이 한 번 시도한다. 버전 충돌이면 커밋 시점에 OptimisticLockingFailureException 이 난다.
     */
    public IssueCouponResponse issue(Long memberId, Long couponId) {
        return transactionTemplate.execute(status -> issueInTransaction(memberId, couponId));
    }

    /**
     * 버전 충돌일 때만 최대 maxAttempts 번까지 다시 시도한다. 시도마다 새 트랜잭션과 새 영속성 컨텍스트에서 쿠폰을 다시 읽는다.
     * 중복, 품절, 데드락 같은 다른 실패는 재시도하지 않는다. onAttempt 는 시도 번호(1부터)를 받는다(측정용).
     */
    public IssueCouponResponse issueWithRetry(Long memberId, Long couponId, IntConsumer onAttempt) {
        // 바깥 트랜잭션 안에서 부르면 모든 시도가 같은 트랜잭션과 영속성 컨텍스트를 써서 옛 version 을 다시 읽는다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("낙관적 락 재시도는 바깥 트랜잭션 없이 호출해야 합니다.");
        }
        for (int attempt = 1; ; attempt++) {
            onAttempt.accept(attempt);
            try {
                return issue(memberId, couponId);
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                sleep(backoff(attempt));
            }
        }
    }

    private IssueCouponResponse issueInTransaction(Long memberId, Long couponId) {
        memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberNotFoundException(ErrorCode.NOT_FOUND_MEMBER));

        // 잠금 없는 SELECT. 이때 읽은 version 이 커밋 시 검사 기준이 된다.
        VersionedCoupon coupon = versionedCouponRepository.findById(couponId)
                .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON));

        if (issueCouponRepository.existsByMemberIdAndCouponId(memberId, couponId)) {
            throw new IsAlreadyIssued(ErrorCode.IS_ALREADY_ISSUED);
        }

        coupon.increaseIssueAmount();

        // INSERT 는 즉시 실행된다. 커밋 직전 flush 에서
        // UPDATE coupons SET ..., version = v + 1 WHERE coupon_id = ? AND version = v 가 실행되고,
        // 영향 행이 0 이면(그 사이 다른 트랜잭션이 version 을 올림) 충돌로 롤백된다.
        IssueCoupon issueCoupon = issueCouponRepository.save(
                IssueCoupon.builder().memberId(memberId).couponId(couponId).build());
        return IssueCouponResponse.fromEntity(issueCoupon);
    }

    // 지수 증가 + 전체 지터: 0 ~ min(max, base * 2^(attempt-1)) ms 중 무작위. 같은 순간 충돌한 요청들이 다시 같은 순간에 재시도하지 않게 한다.
    private long backoff(int attempt) {
        long cap = Math.min(backoffMaxMs, backoffBaseMs << Math.min(attempt - 1, 20));
        return ThreadLocalRandom.current().nextLong(cap + 1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
