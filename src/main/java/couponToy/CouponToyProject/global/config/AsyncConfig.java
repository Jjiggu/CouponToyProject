package couponToy.CouponToyProject.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "couponIssueEventExecutor")
    public Executor couponIssueEventExecutor(
            @Value("${coupon.issue.event.core-pool-size:4}") int corePoolSize,
            @Value("${coupon.issue.event.max-pool-size:8}") int maxPoolSize,
            @Value("${coupon.issue.event.queue-capacity:50000}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("coupon-issue-event-");
        executor.initialize();
        return executor;
    }
}
