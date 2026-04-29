package couponToy.CouponToyProject.CouponIssue.event;

import couponToy.CouponToyProject.Coupon.model.Coupon;
import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.CouponIssue.model.IssueCoupon;
import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.CouponIssue.repository.IssueCouponRepository;
import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.Member.repository.MemberRepository;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.exception.MemberNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class CouponIssueEventListener {

    private final IssueCouponRepository issueCouponRepository;
    private final MemberRepository memberRepository;
    private final CouponRepository couponRepository;
    private final CouponIssuedRedisRepository couponIssuedRedisRepository;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleCouponIssued(CouponIssuedEvent event) {
        try {
            Member member = memberRepository.findById(event.memberId())
                    .orElseThrow(() -> new MemberNotFoundException(ErrorCode.NOT_FOUND_MEMBER));
            Coupon coupon = couponRepository.findById(event.couponId())
                    .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON));
            issueCouponRepository.save(new IssueCoupon(member, coupon));
        } catch (Exception e) {
            couponIssuedRedisRepository.remove(event.couponId(), event.memberId());
        }
    }
}
