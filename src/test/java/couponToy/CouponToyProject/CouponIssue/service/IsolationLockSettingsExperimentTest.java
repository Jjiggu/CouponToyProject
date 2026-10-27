package couponToy.CouponToyProject.CouponIssue.service;

import com.zaxxer.hikari.HikariDataSource;
import couponToy.CouponToyProject.CouponIssue.support.ConcurrencyExperimentSupport;
import couponToy.CouponToyProject.Lab.service.IssueOutcome;
import couponToy.CouponToyProject.Lab.service.LabService.LabResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1단계 변형: MySQL 전역 설정 innodb_deadlock_detect, innodb_lock_wait_timeout 을 바꿔 실행한다.
 * 같은 MySQL 의 다른 세션에도 영향을 주므로 LAB_CHANGE_MYSQL_GLOBALS=true 일 때만 실행하고,
 * 실험마다 원래 값으로 되돌린다. (SET GLOBAL 값은 MySQL 을 재시작해도 기본값으로 돌아간다)
 * 실행 결과는 build/lab/stage1-lock-settings.md 에 쌓인다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LAB_CHANGE_MYSQL_GLOBALS", matches = "true")
class IsolationLockSettingsExperimentTest extends ConcurrencyExperimentSupport {

    private static final Path REPORT = Path.of("build/lab/stage1-lock-settings.md");

    @BeforeAll
    static void createReport() throws IOException {
        createReport(REPORT, "1단계 MySQL 락 설정 변형 실행 기록");
    }

    @Override
    protected Path reportPath() {
        return REPORT;
    }

    @ParameterizedTest(name = "데드락 감지 OFF, innodb_lock_wait_timeout={0}")
    @ValueSource(ints = {3, 50})
    @DisplayName("SERIALIZABLE 두 트랜잭션: 데드락 감지를 끄면 서로 기다리다 락 대기 시간 초과로 끝난다")
    void deadlockDetectionOff(int lockWaitTimeout) throws Exception {
        withLockSettings(false, lockWaitTimeout, () -> {
            Long couponId = labCoupon(10);
            List<Long> members = List.of(createMember(1).getMemberId(), createMember(2).getMemberId());

            Interleaving run = runWhileCouponRowLocked(CouponIssueStrategy.SERIALIZABLE, true, couponId, members);
            reportInterleaving("SERIALIZABLE, 재고 10, 서로 다른 회원 2명, innodb_deadlock_detect=OFF, innodb_lock_wait_timeout="
                    + lockWaitTimeout, run);

            assertThat(run.outcomes()).doesNotContain(IssueOutcome.DEADLOCK).contains(IssueOutcome.LOCK_WAIT_TIMEOUT);
            assertThat(run.issueRows()).isEqualTo(run.outcomes().stream().filter(o -> o == IssueOutcome.SUCCESS).count());
            assertThat(run.issuedCount()).isEqualTo((int) run.issueRows());
            assertThat(run.elapsedAfterReleaseMs()).allMatch(ms -> ms >= lockWaitTimeout * 1000L);
            // 락 대기 시간 초과도 PessimisticLockingFailureException 계열로 올라와 HTTP 503 으로 매핑된다.
            assertThat(run.failures()).allMatch(e -> e instanceof PessimisticLockingFailureException);
        });
    }

    @ParameterizedTest(name = "데드락 감지 {0}, innodb_lock_wait_timeout={1}, {2}: {3}")
    @CsvSource(delimiter = '|', textBlock = """
            # 데드락 감지 | 락 대기 초 | 방식          | 시나리오  | 재고 | 회원 | 회원당 요청
            false         | 3          | SERIALIZABLE    | 재고 충분 | 50   | 50   | 1
            false         | 3          | REPEATABLE_READ | 재고 충분 | 50   | 50   | 1
            true          | 1          | SERIALIZABLE    | 재고 충분 | 50   | 50   | 1
            true          | 1          | REPEATABLE_READ | 재고 충분 | 50   | 50   | 1
            """)
    @DisplayName("동시 요청 결과 기록")
    void concurrentRequests(boolean deadlockDetect, int lockWaitTimeout, CouponIssueStrategy method, String scenario,
                            int totalCount, int memberCount, int requestsPerMember) throws Exception {
        int requestCount = memberCount * requestsPerMember;
        withLockSettings(deadlockDetect, lockWaitTimeout, () -> {
            for (int round = 1; round <= 3; round++) {
                Burst burst = burst(method, true, totalCount, memberCount, requestsPerMember, round);
                reportBurst(method + ", " + scenario + ", innodb_deadlock_detect=" + (deadlockDetect ? "ON" : "OFF")
                        + ", innodb_lock_wait_timeout=" + lockWaitTimeout, round, requestCount, burst);

                LabResult result = burst.result();
                assertThat(result.requests().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(requestCount);
                assertThat(result.requests()).doesNotContainKey(IssueOutcome.ERROR);
                assertThat(result.checks().atMostOncePerMember()).isTrue();
                assertThat(result.issuedCount()).isLessThanOrEqualTo((int) result.issueRows());
                if (!deadlockDetect) {
                    assertThat(result.requests()).doesNotContainKey(IssueOutcome.DEADLOCK);
                }
                if (method == CouponIssueStrategy.SERIALIZABLE) {
                    assertThat(result.checks().notOverIssued()).isTrue();
                    assertThat(result.checks().issuedCountMatchesRows()).isTrue();
                }
            }
            append("\n");
        });
    }

    private interface Experiment {
        void run() throws Exception;
    }

    private void withLockSettings(boolean deadlockDetect, int lockWaitTimeout, Experiment experiment) throws Exception {
        Map<String, Object> original = jdbcTemplate.queryForMap(
                "SELECT @@GLOBAL.innodb_deadlock_detect AS detect, @@GLOBAL.innodb_lock_wait_timeout AS timeout");
        try {
            jdbcTemplate.execute("SET GLOBAL innodb_deadlock_detect = " + (deadlockDetect ? "ON" : "OFF"));
            jdbcTemplate.execute("SET GLOBAL innodb_lock_wait_timeout = " + lockWaitTimeout);
            renewConnections();
            assertThat(jdbcTemplate.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class))
                    .isEqualTo(lockWaitTimeout);
            experiment.run();
        } finally {
            jdbcTemplate.execute("SET GLOBAL innodb_deadlock_detect = " + original.get("detect"));
            jdbcTemplate.execute("SET GLOBAL innodb_lock_wait_timeout = " + original.get("timeout"));
            renewConnections();
        }
    }

    // innodb_lock_wait_timeout 은 커넥션을 만들 때 세션 값으로 복사된다. 풀의 커넥션을 버려야 새 값이 적용된다.
    // 버린 직후에는 풀이 비어 1회차 요청만 커넥션 생성을 기다리며 동시성이 낮아지므로, 최대 크기까지 미리 채운다.
    private void renewConnections() throws SQLException {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        hikari.getHikariPoolMXBean().softEvictConnections();
        List<Connection> connections = new ArrayList<>();
        try {
            for (int i = 0; i < hikari.getMaximumPoolSize(); i++) {
                connections.add(hikari.getConnection());
            }
        } finally {
            for (Connection connection : connections) {
                connection.close();
            }
        }
    }
}
