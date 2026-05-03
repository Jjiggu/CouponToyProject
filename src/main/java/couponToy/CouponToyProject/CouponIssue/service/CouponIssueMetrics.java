package couponToy.CouponToyProject.CouponIssue.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class CouponIssueMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger eventInflight;

    public CouponIssueMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.eventInflight = new AtomicInteger(0);
        meterRegistry.gauge("coupon_issue_event_inflight", eventInflight);
    }

    public Timer.Sample start() {
        return Timer.start(meterRegistry);
    }

    public void recordRequest(CouponIssueStrategy strategy, CouponIssueResult result, Timer.Sample sample) {
        Counter.builder("coupon_issue_requests_total")
                .tag("strategy", strategy.getTag())
                .tag("result", result.getTag())
                .register(meterRegistry)
                .increment();

        sample.stop(Timer.builder("coupon_issue_duration_seconds")
                .tag("strategy", strategy.getTag())
                .register(meterRegistry));
    }

    public <T> T recordDb(CouponIssueStrategy strategy, Callable<T> callable) {
        try {
            return Timer.builder("coupon_issue_db_duration_seconds")
                    .tag("strategy", strategy.getTag())
                    .register(meterRegistry)
                    .recordCallable(callable);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public <T> T recordRedis(CouponIssueStrategy strategy, Callable<T> callable) {
        try {
            return Timer.builder("coupon_issue_redis_duration_seconds")
                    .tag("strategy", strategy.getTag())
                    .register(meterRegistry)
                    .recordCallable(callable);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public void recordEventPublished() {
        Counter.builder("coupon_issue_event_published_total")
                .register(meterRegistry)
                .increment();
        eventInflight.incrementAndGet();
    }

    public void recordEventCompleted(Duration consistencyLag) {
        Counter.builder("coupon_issue_event_completed_total")
                .register(meterRegistry)
                .increment();
        recordConsistencyLag(consistencyLag);
        decrementInflight();
    }

    public void recordEventFailed(Duration consistencyLag) {
        Counter.builder("coupon_issue_event_failed_total")
                .register(meterRegistry)
                .increment();
        recordConsistencyLag(consistencyLag);
        decrementInflight();
    }

    private void recordConsistencyLag(Duration consistencyLag) {
        Timer.builder("coupon_issue_consistency_lag_seconds")
                .register(meterRegistry)
                .record(consistencyLag);
    }

    private void decrementInflight() {
        eventInflight.updateAndGet(value -> Math.max(0, value - 1));
    }
}
