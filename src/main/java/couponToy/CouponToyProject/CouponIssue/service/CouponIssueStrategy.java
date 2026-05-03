package couponToy.CouponToyProject.CouponIssue.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CouponIssueStrategy {
    PESSIMISTIC("pessimistic"),
    REDIS_TRANSACTIONAL("redis_transactional"),
    REDIS_EVENT("redis_event");

    private final String tag;
}
