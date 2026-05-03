package couponToy.CouponToyProject.CouponIssue.event;

import java.time.Instant;

public record CouponIssuedEvent(Long memberId, Long couponId, Instant requestedAt) {
}
