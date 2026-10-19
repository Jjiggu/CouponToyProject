package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.CouponIssue.support.AbstractCouponIssueIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 4단계 사전 확인: 기존 이벤트 방식이 기대던 @TransactionalEventListener 가 트랜잭션 유무에 따라 실제로 어떻게 도는지.
 * 발급 요청 경로(Redis 예약)에는 DB 트랜잭션이 없다.
 */
@SpringBootTest
@Import(TransactionalEventListenerTest.Listeners.class)
class TransactionalEventListenerTest extends AbstractCouponIssueIntegrationTestSupport {

    public record Published(String id) {
    }

    // 리스너:이벤트 -> "실행 스레드, 그 스레드에 트랜잭션이 있었는지"
    public static class Listeners {
        private final Map<String, String> calls = new ConcurrentHashMap<>();

        // @Async 때문에 이 빈은 CGLIB 프록시다. 프록시의 필드는 비어 있으므로 메서드로 읽어야 실제 객체에 닿는다.
        public Map<String, String> calls() {
            return calls;
        }

        @TransactionalEventListener // phase = AFTER_COMMIT, fallbackExecution = false
        public void afterCommit(Published event) {
            record("afterCommit", event);
        }

        @TransactionalEventListener(fallbackExecution = true)
        public void afterCommitOrNow(Published event) {
            record("fallback", event);
        }

        @Async
        @TransactionalEventListener(fallbackExecution = true)
        public void asyncAfterCommitOrNow(Published event) {
            record("async", event);
        }

        private void record(String listener, Published event) {
            calls.put(listener + ":" + event.id(),
                    Thread.currentThread().getName() + ", tx=" + TransactionSynchronizationManager.isActualTransactionActive());
        }
    }

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private Listeners listeners;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ThreadPoolTaskExecutor executor;

    private String caller;

    @BeforeEach
    void reset() {
        caller = Thread.currentThread().getName();
        listeners.calls().clear();
    }

    @Test
    void 트랜잭션_없이_발행하면_AFTER_COMMIT_리스너는_조용히_건너뛴다() {
        publisher.publishEvent(new Published("no-tx"));

        assertThat(listeners.calls()).doesNotContainKey("afterCommit:no-tx"); // 예외도, INFO 로그도 없다
        assertThat(listeners.calls().get("fallback:no-tx")).isEqualTo(caller + ", tx=false"); // 발행 즉시, 발행한 스레드에서
        await().until(() -> listeners.calls().containsKey("async:no-tx"));
        assertThat(listeners.calls().get("async:no-tx")).startsWith("coupon-issue-async-").endsWith("tx=false");
    }

    @Test
    void 커밋되면_커밋_뒤에_실행되고_롤백되면_실행되지_않는다() {
        long submittedBefore = executor.getThreadPoolExecutor().getTaskCount();

        transactionTemplate.executeWithoutResult(status -> {
            publisher.publishEvent(new Published("commit"));
            assertThat(listeners.calls()).isEmpty(); // 커밋 전에는 아무것도 실행되지 않는다
        });
        assertThat(listeners.calls().get("afterCommit:commit")).isEqualTo(caller + ", tx=true");
        assertThat(listeners.calls().get("fallback:commit")).isEqualTo(caller + ", tx=true");
        await().until(() -> listeners.calls().containsKey("async:commit"));
        assertThat(listeners.calls().get("async:commit")).endsWith("tx=false");

        transactionTemplate.executeWithoutResult(status -> {
            publisher.publishEvent(new Published("rollback"));
            status.setRollbackOnly();
        });
        assertThat(listeners.calls()).doesNotContainKeys("afterCommit:rollback", "fallback:rollback");
        // 비동기 리스너는 커밋 시점에 풀로 넘겨진다. 넘겨진 작업 수가 커밋 건 하나뿐이면 롤백 건은 넘겨지지 않은 것이다.
        assertThat(executor.getThreadPoolExecutor().getTaskCount() - submittedBefore).isEqualTo(1);
    }
}
