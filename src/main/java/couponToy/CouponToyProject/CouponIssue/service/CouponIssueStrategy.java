package couponToy.CouponToyProject.CouponIssue.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CouponIssueStrategy {
    READ_COMMITTED("read_committed"),
    REPEATABLE_READ("repeatable_read"),
    SERIALIZABLE("serializable"),
    PESSIMISTIC("pessimistic"),
    OPTIMISTIC("optimistic"),
    OPTIMISTIC_RETRY("optimistic_retry"),
    REDIS_TRANSACTIONAL("redis_transactional"),
    REDIS_EVENT("redis_event");

    private final String tag;
}
