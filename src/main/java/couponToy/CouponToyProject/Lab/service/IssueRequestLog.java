package couponToy.CouponToyProject.Lab.service;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 쿠폰 하나에 대해 단일 진입점(LabService.issue)에서 관찰한 요청 기록. 애플리케이션 메모리에만 있다.
 * - 도착 순서: 진입 시 AtomicLong 을 증가시킨 순서
 * - 완료 순서: 동기 발급이 커밋을 마치고 반환된 순서 (비동기 접수는 완료 순서가 없다)
 *
 * ponytail: 요청마다 Entry 를 무제한 쌓는다. 수천~수만 건 실험용이다.
 * step/soak 처럼 수백만 건을 보내면 수백 MB 가 되므로, 그때 상한이나 샘플링을 넣는다.
 */
public class IssueRequestLog {

    public record Entry(long arrivalSeq, long memberId, IssueOutcome outcome, long elapsedNanos, long completionSeq) {
    }

    public record Ordering(
            long validRequests,              // 회원별 첫 요청 수 (NOT_FOUND 제외)
            long firstNValidWon,             // 먼저 도착한 유효 요청 N(= min(재고, 유효 요청))명 중 실제 발급된 수
            long winnersOutsideFirstN,       // 실제 발급됐지만 먼저 도착한 N명에 들지 못한 수
            long completedOutOfArrivalOrder  // SUCCESS 중 도착 순위와 완료 순위가 다른 수
    ) {
    }

    public record Latency(long count, double p50, double p95, double p99, double max) {
    }

    private final AtomicLong arrivalSeq = new AtomicLong();
    private final AtomicLong completionSeq = new AtomicLong();
    private final Queue<Entry> entries = new ConcurrentLinkedQueue<>();
    private final Map<String, Long> lockMetricsAtStart;

    public IssueRequestLog(Map<String, Long> lockMetricsAtStart) {
        this.lockMetricsAtStart = lockMetricsAtStart;
    }

    public long arrive() {
        return arrivalSeq.incrementAndGet();
    }

    public void complete(long arrival, long memberId, IssueOutcome outcome, long elapsedNanos) {
        long completion = outcome == IssueOutcome.SUCCESS ? completionSeq.incrementAndGet() : 0;
        entries.add(new Entry(arrival, memberId, outcome, elapsedNanos, completion));
    }

    public Map<String, Long> lockMetricsAtStart() {
        return lockMetricsAtStart;
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public Map<IssueOutcome, Long> countByOutcome() {
        return entries.stream().collect(Collectors.groupingBy(Entry::outcome, () -> new EnumMap<>(IssueOutcome.class), Collectors.counting()));
    }

    public Ordering ordering(Collection<Long> winnerMemberIds, int totalCount) {
        Map<Long, Long> firstArrivalByMember = entries.stream()
                .filter(e -> e.outcome() != IssueOutcome.NOT_FOUND)
                .collect(Collectors.toMap(Entry::memberId, Entry::arrivalSeq, Math::min));
        List<Long> validInArrivalOrder = firstArrivalByMember.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();

        int n = Math.min(totalCount, validInArrivalOrder.size());
        Set<Long> firstN = new HashSet<>(validInArrivalOrder.subList(0, n));
        Set<Long> winners = new HashSet<>(winnerMemberIds);

        // 완료 순번은 SUCCESS 에만 1..k 로 붙는다. 도착 순으로 정렬했을 때 i 번째가 i 번째로 완료됐는지 본다.
        List<Entry> successesByArrival = entries.stream()
                .filter(e -> e.completionSeq() > 0)
                .sorted((a, b) -> Long.compare(a.arrivalSeq(), b.arrivalSeq()))
                .toList();
        long outOfOrder = 0;
        for (int i = 0; i < successesByArrival.size(); i++) {
            if (successesByArrival.get(i).completionSeq() != i + 1) outOfOrder++;
        }

        return new Ordering(
                validInArrivalOrder.size(),
                firstN.stream().filter(winners::contains).count(),
                winners.stream().filter(w -> !firstN.contains(w)).count(),
                outOfOrder
        );
    }

    public Latency latencyMs(boolean successOnly) {
        double[] millis = entries.stream()
                .filter(e -> !successOnly || e.outcome() == IssueOutcome.SUCCESS)
                .mapToDouble(e -> e.elapsedNanos() / 1_000_000.0)
                .sorted()
                .toArray();
        if (millis.length == 0) return new Latency(0, 0, 0, 0, 0);
        return new Latency(millis.length, rank(millis, 0.50), rank(millis, 0.95), rank(millis, 0.99), millis[millis.length - 1]);
    }

    // nearest-rank 백분위
    private static double rank(double[] sorted, double p) {
        return sorted[Math.max(0, (int) Math.ceil(p * sorted.length) - 1)];
    }
}
