package couponToy.CouponToyProject.CouponIssue.service;

public enum CouponIssueStrategy {
    READ_COMMITTED,
    REPEATABLE_READ,
    SERIALIZABLE,
    PESSIMISTIC,
    OPTIMISTIC,
    OPTIMISTIC_RETRY,
    REDIS_SET_ONLY,
    REDIS_MULTI_EXEC,
    REDIS_WATCH,
    REDIS_LUA,
    REDIS_ASYNC,
    REDIS_PUBSUB,
    REDIS_STREAM,
    REDIS_STREAM_ATOMIC
}
