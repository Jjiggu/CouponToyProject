package couponToy.CouponToyProject.Lab.service;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 쿠폰 하나에 대해 단일 진입점(LabService.issue)에서 관찰한 요청 기록. 애플리케이션 메모리에만 있다.
 * - 도착 순서: 진입 시 AtomicLong 을 증가시킨 순서
 * - 완료 순서: DB 확정이 커밋을 마친 순서. 동기 방식은 반환 시점, 비동기(REDIS_ASYNC)는 풀 스레드가 확정을 끝낸 시점
 * - 비동기 요청은 두 번 기록된다: 진입점이 돌려준 접수 결과(entries)와, 나중에 풀 스레드가 끝낸 확정 결과(asyncEntries)
 *
 * ponytail: 요청마다 Entry 를 무제한 쌓는다. 수천~수만 건 실험용이다.
 * step/soak 처럼 수백만 건을 보내면 수백 MB 가 되므로, 그때 상한이나 샘플링을 넣는다.
 */
public class IssueRequestLog {

    public record Entry(long arrivalSeq, long memberId, IssueOutcome outcome, long elapsedNanos, long completionSeq, int attempts) {
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

    public record Attempts(
            Map<Integer, Long> histogram,  // 시도 횟수 -> 요청 수
            long total,                    // 전체 트랜잭션 시도 수
            long versionConflicts          // 버전 충돌로 끝난 시도 수
    ) {
    }

    private final AtomicLong arrivalSeq = new AtomicLong();
    private final AtomicLong completionSeq = new AtomicLong();
    private final Queue<Entry> entries = new ConcurrentLinkedQueue<>();
    private final Queue<Entry> asyncEntries = new ConcurrentLinkedQueue<>();
    private final Map<String, Long> lockMetricsAtStart;

    public IssueRequestLog(Map<String, Long> lockMetricsAtStart) {
        this.lockMetricsAtStart = lockMetricsAtStart;
    }

    public long arrive() {
        return arrivalSeq.incrementAndGet();
    }

    public void complete(long arrival, long memberId, IssueOutcome outcome, long elapsedNanos, int attempts) {
        long completion = outcome == IssueOutcome.SUCCESS ? completionSeq.incrementAndGet() : 0;
        entries.add(new Entry(arrival, memberId, outcome, elapsedNanos, completion, attempts));
    }

    // elapsedNanos 는 진입 → 확정 끝(커밋 또는 실패). 접수(ACCEPTED) 기록 뒤에만 불린다.
    public void finishAsync(long arrival, long memberId, IssueOutcome outcome, long elapsedNanos) {
        long completion = outcome == IssueOutcome.SUCCESS ? completionSeq.incrementAndGet() : 0;
        asyncEntries.add(new Entry(arrival, memberId, outcome, elapsedNanos, completion, 1));
    }

    public Map<IssueOutcome, Long> asyncOutcomes() {
        return count(asyncEntries);
    }

    // 접수됐지만 확정 결과가 아직 없는 요청: 큐에서 기다리거나, 처리 중이거나, 사라졌다
    // 확정 결과 수를 먼저 센다. 접수 기록이 확정 결과보다 먼저 쌓이므로, 이 순서면 음수가 되지 않고 0 이면 정말 모두 끝난 것이다.
    public long pending() {
        long finished = asyncEntries.size();
        return entries.stream().filter(e -> e.outcome() == IssueOutcome.ACCEPTED).count() - finished;
    }

    public Map<String, Long> lockMetricsAtStart() {
        return lockMetricsAtStart;
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public Map<IssueOutcome, Long> countByOutcome() {
        return count(entries);
    }

    private static Map<IssueOutcome, Long> count(Collection<Entry> entries) {
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
        List<Entry> successesByArrival = Stream.concat(entries.stream(), asyncEntries.stream())
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

    // 재시도는 버전 충돌일 때만 하므로, 마지막 시도를 뺀 나머지는 모두 충돌이다. 마지막 시도도 충돌이면 하나 더한다.
    public Attempts attempts() {
        Map<Integer, Long> histogram = entries.stream()
                .collect(Collectors.groupingBy(Entry::attempts, TreeMap::new, Collectors.counting()));
        long total = entries.stream().mapToLong(Entry::attempts).sum();
        long conflicts = entries.stream()
                .mapToLong(e -> e.attempts() - 1 + (e.outcome() == IssueOutcome.OPTIMISTIC_CONFLICT ? 1 : 0))
                .sum();
        return new Attempts(histogram, total, conflicts);
    }

    public Latency latencyMs(boolean successOnly) {
        return latency(entries.stream().filter(e -> !successOnly || e.outcome() == IssueOutcome.SUCCESS));
    }

    // 비동기 요청의 진입 → DB 커밋 (SUCCESS 만)
    public Latency confirmLatencyMs() {
        return latency(asyncEntries.stream().filter(e -> e.outcome() == IssueOutcome.SUCCESS));
    }

    private static Latency latency(Stream<Entry> selected) {
        double[] millis = selected
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
