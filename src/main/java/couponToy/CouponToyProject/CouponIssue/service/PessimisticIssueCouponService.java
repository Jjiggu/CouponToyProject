package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.model.IssueCoupon;
import couponToy.CouponToyProject.CouponIssue.repository.IssueCouponRepository;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import couponToy.CouponToyProject.global.exception.IsAlreadyIssued;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PessimisticIssueCouponService {

    private final IssueCouponRepository issueCouponRepository;
    private final MemberRepository memberRepository;
    private final CouponRepository couponRepository;
    private final CouponIssueMetrics metrics;

    @Transactional
    public IssueCouponResponse issueCoupon(Long memberId, Long couponId) {
        CouponIssueStrategy strategy = CouponIssueStrategy.PESSIMISTIC;
        Timer.Sample sample = metrics.start();

        try {
            IssueCouponResponse response = metrics.recordDb(strategy, () -> {
                Member member = memberRepository.findById(memberId)
                        .orElseThrow(() -> new MemberNotFoundException(ErrorCode.NOT_FOUND_MEMBER));

                Coupon coupon = couponRepository.findByCouponIdForUpdate(couponId)
                        .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON));

                if (issueCouponRepository.existsByMemberIdAndCouponId(memberId, couponId)) {
                    throw new IsAlreadyIssued(ErrorCode.IS_ALREADY_ISSUED);
                }

                coupon.increaseIssueAmount();
                IssueCoupon issueCoupon = issueCouponRepository.save(new IssueCoupon(member, coupon));

                return IssueCouponResponse.fromEntity(issueCoupon);
            });
            metrics.recordRequest(strategy, CouponIssueResult.SUCCESS, sample);
            return response;
        } catch (IsAlreadyIssued ex) {
            metrics.recordRequest(strategy, CouponIssueResult.DUPLICATE, sample);
            throw ex;
        } catch (CouponSoldOutException ex) {
            metrics.recordRequest(strategy, CouponIssueResult.SOLD_OUT, sample);
            throw ex;
        } catch (Exception ex) {
            metrics.recordRequest(strategy, CouponIssueResult.ERROR, sample);
            throw ex;
        }
    }
}
