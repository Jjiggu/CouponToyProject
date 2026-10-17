package couponToy.CouponToyProject.Lab.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IssueRequestLogTest {

    @Test
    @DisplayName("먼저 도착한 유효 요청 N명과 실제 당첨자, 도착/완료 순서를 비교한다")
    void ordering() {
        IssueRequestLog log = new IssueRequestLog(Map.of());
        long a1 = log.arrive(); // member 1
        long a2 = log.arrive(); // member 2
        long a3 = log.arrive(); // member 3
        long a4 = log.arrive(); // member 1 재요청 -> 유효 요청 아님
        long a5 = log.arrive(); // member 4
        long a6 = log.arrive(); // member 99 (없는 회원) -> 유효 요청 아님

        // 완료 순서: 2, 1, 4 순으로 커밋
        log.complete(a2, 2, IssueOutcome.SUCCESS, 1_000_000);
        log.complete(a1, 1, IssueOutcome.SUCCESS, 2_000_000);
        log.complete(a3, 3, IssueOutcome.DEADLOCK, 3_000_000);
        log.complete(a4, 1, IssueOutcome.DUPLICATE, 4_000_000);
        log.complete(a5, 4, IssueOutcome.SUCCESS, 5_000_000);
        log.complete(a6, 99, IssueOutcome.NOT_FOUND, 6_000_000);

        IssueRequestLog.Ordering ordering = log.ordering(List.of(1L, 2L, 4L), 3);

        assertThat(ordering.validRequests()).isEqualTo(4);        // member 1, 2, 3, 4
        assertThat(ordering.firstNValidWon()).isEqualTo(2);       // 먼저 온 3명(1,2,3) 중 1,2 당첨
        assertThat(ordering.winnersOutsideFirstN()).isEqualTo(1); // member 4
        assertThat(ordering.completedOutOfArrivalOrder()).isEqualTo(2); // 도착 1,2 가 완료 2,1

        assertThat(log.countByOutcome()).containsEntry(IssueOutcome.SUCCESS, 3L).containsEntry(IssueOutcome.DEADLOCK, 1L);
        assertThat(log.latencyMs(true).count()).isEqualTo(3);
        assertThat(log.latencyMs(true).max()).isEqualTo(5.0);
        assertThat(log.latencyMs(false).p50()).isEqualTo(3.0);
    }
}
