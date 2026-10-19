package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.CouponIssue.service.RedisIssueCouponService.ReservationMethod;
import lombok.RequiredArgsConstructor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 4단계: Redis 예약(3단계 Lua)까지는 요청 스레드에서 하고, DB 확정은 스레드 풀(couponIssueExecutor)에 넘긴 뒤 바로 돌아온다.
 * 돌아왔다는 것은 접수일 뿐 발급 완료가 아니다. 확정 결과는 반환한 future 로만 알 수 있다.
 */
@Service
@RequiredArgsConstructor
public class AsyncIssueCouponService {

    private final RedisIssueCouponService redisIssueCouponService;
    private final CouponIssuedRedisRepository couponIssuedRedisRepository;

    public CompletableFuture<IssueCouponResponse> issue(Long memberId, Long couponId) {
        redisIssueCouponService.reserve(memberId, couponId, ReservationMethod.LUA, attempt -> {
        });
        try {
            return redisIssueCouponService.confirmLater(memberId, couponId);
        } catch (TaskRejectedException e) {
            // 풀 큐가 가득 차 작업을 넣지 못했다. 접수하지 않았으므로 예약을 되돌린다.
            couponIssuedRedisRepository.remove(couponId, memberId);
            throw e;
        }
    }
}
