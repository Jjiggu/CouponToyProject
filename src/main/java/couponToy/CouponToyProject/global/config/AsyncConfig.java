package couponToy.CouponToyProject.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 4단계: 접수 뒤 DB 확정을 맡는 스레드 풀. 큐는 이 JVM 메모리에 있다.
 * - 스레드 pool-size 개 고정(core = max). ThreadPoolExecutor 는 큐가 가득 찬 뒤에야 core 를 넘겨 스레드를 늘리므로 둘을 나누지 않는다
 * - 큐 queue-capacity 개. 가득 차면 AbortPolicy(기본값): 작업을 넣으려던 요청 스레드에서 TaskRejectedException
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public ThreadPoolTaskExecutor couponIssueExecutor(
            @Value("${coupon.issue.async.pool-size:4}") int poolSize,
            @Value("${coupon.issue.async.queue-capacity:50000}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("coupon-issue-async-");
        return executor;
    }
}
