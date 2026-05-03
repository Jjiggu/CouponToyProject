package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponRequest;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.model.IssueCoupon;
import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.CouponIssue.repository.IssueCouponRepository;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import couponToy.CouponToyProject.global.exception.IsAlreadyIssued;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import couponToy.CouponToyProject.global.security.MemberDetails;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IssueCouponService {

    private final IssueCouponRepository issueCouponRepository;
    private final MemberRepository memberRepository;
    private final CouponRepository couponRepository;
    private final CouponIssuedRedisRepository couponIssuedRedisRepository;
    private final CouponIssueMetrics metrics;

    @Transactional
    public IssueCouponResponse issueCoupon(IssueCouponRequest issueCouponRequest, MemberDetails memberDetails, Long couponId) {
        Long userId = issueCouponRequest != null && issueCouponRequest.getMemberId() != null
                ? issueCouponRequest.getMemberId()
                : memberDetails.getMemberId();
        return issueCoupon(userId, couponId);
    }

    @Transactional
    public IssueCouponResponse issueCoupon(MemberDetails memberDetails, Long couponId) {
        return issueCoupon(memberDetails.getMemberId(), couponId);
    }

    private IssueCouponResponse issueCoupon(Long userId, Long couponId) {
        CouponIssueStrategy strategy = CouponIssueStrategy.REDIS_TRANSACTIONAL;
        Timer.Sample sample = metrics.start();

        try {
            int totalCount = findTotalCount(strategy, couponId);

            metrics.recordRedis(strategy, () -> {
                couponIssuedRedisRepository.checkAndRegister(couponId, userId, totalCount);
                return null;
            });

            IssueCouponResponse response = metrics.recordDb(strategy, () -> {
                Member member = memberRepository.findById(userId)
                        .orElseThrow(() -> new MemberNotFoundException(ErrorCode.NOT_FOUND_MEMBER));

                Coupon coupon = couponRepository.findById(couponId)
                        .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON));

                IssueCoupon issueCoupon = issueCouponRepository.save(new IssueCoupon(member, coupon));
                int updated = couponRepository.increaseIssuedCount(couponId);
                if (updated != 1) {
                    throw new CouponSoldOutException(ErrorCode.COUPON_SOLD_OUT);
                }

                return IssueCouponResponse.fromEntity(issueCoupon);
            });

            metrics.recordRequest(strategy, CouponIssueResult.SUCCESS, sample);
            return response;
        } catch (IsAlreadyIssued ex) {
            metrics.recordRequest(strategy, CouponIssueResult.DUPLICATE, sample);
            throw ex;
        } catch (CouponSoldOutException ex) {
            couponIssuedRedisRepository.remove(couponId, userId);
            metrics.recordRequest(strategy, CouponIssueResult.SOLD_OUT, sample);
            throw ex;
        } catch (DataIntegrityViolationException ex) {
            couponIssuedRedisRepository.remove(couponId, userId);
            metrics.recordRequest(strategy, CouponIssueResult.DUPLICATE, sample);
            throw new IsAlreadyIssued(ErrorCode.IS_ALREADY_ISSUED);
        } catch (Exception e) {
            couponIssuedRedisRepository.remove(couponId, userId);
            metrics.recordRequest(strategy, CouponIssueResult.ERROR, sample);
            throw e;
        }
    }

    private int findTotalCount(CouponIssueStrategy strategy, Long couponId) {
        return metrics.recordRedis(strategy, () -> couponIssuedRedisRepository.getTotalCount(couponId))
                .orElseGet(() -> {
                    Coupon coupon = metrics.recordDb(strategy, () -> couponRepository.findById(couponId)
                            .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON)));
                    metrics.recordRedis(strategy, () -> {
                        couponIssuedRedisRepository.setTotalCount(couponId, coupon.getTotalCount());
                        return null;
                    });
                    return coupon.getTotalCount();
                });
    }
}
