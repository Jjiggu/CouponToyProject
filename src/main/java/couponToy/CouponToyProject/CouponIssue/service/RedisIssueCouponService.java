package couponToy.CouponToyProject.CouponIssue.service;

import couponToy.CouponToyProject.Coupon.repository.CouponRepository;
import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponResponse;
import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository;
import couponToy.CouponToyProject.CouponIssue.repository.CouponIssuedRedisRepository.Reservation;
import couponToy.CouponToyProject.global.constant.ErrorCode;
import couponToy.CouponToyProject.global.exception.CouponNotFoundException;
import couponToy.CouponToyProject.global.exception.CouponSoldOutException;
import couponToy.CouponToyProject.global.exception.IsAlreadyIssued;
import couponToy.CouponToyProject.global.failure.FailurePoints;
import couponToy.CouponToyProject.global.failure.FailurePoints.Point;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.function.IntConsumer;

/**
 * 3단계: Redis 에서 중복·수량을 판단해 발급 자격을 예약한 뒤, DB 에 확정한다.
 * Redis 와 MySQL 은 하나의 트랜잭션이 아니다. 이 메서드에는 DB 트랜잭션이 없고, 확정만 별도 트랜잭션(IssueConfirmService)이다.
 */
@Service
@RequiredArgsConstructor
public class RedisIssueCouponService {

    public enum ReservationMethod { SET_ONLY, MULTI_EXEC, WATCH, LUA }

    private final CouponIssuedRedisRepository couponIssuedRedisRepository;
    private final CouponRepository couponRepository;
    private final IssueConfirmService issueConfirmService;
    private final FailurePoints failurePoints;

    public IssueCouponResponse issue(Long memberId, Long couponId, ReservationMethod method, IntConsumer onAttempt) {
        int totalCount = totalCount(couponId);

        Reservation reservation = switch (method) {
            case SET_ONLY -> couponIssuedRedisRepository.reserveWithSetOnly(couponId, memberId, totalCount);
            case MULTI_EXEC -> couponIssuedRedisRepository.reserveWithMultiExec(couponId, memberId, totalCount);
            case WATCH -> couponIssuedRedisRepository.reserveWithWatch(couponId, memberId, totalCount, onAttempt);
            case LUA -> couponIssuedRedisRepository.reserveWithLua(couponId, memberId, totalCount);
        };
        if (reservation == Reservation.DUPLICATE) {
            throw new IsAlreadyIssued(ErrorCode.IS_ALREADY_ISSUED);
        }
        if (reservation == Reservation.SOLD_OUT) {
            throw new CouponSoldOutException(ErrorCode.COUPON_SOLD_OUT);
        }

        // 여기서 프로세스가 죽으면 Redis 예약만 남는다. 되돌릴 근거가 Redis 와 DB 어디에도 없다.
        failurePoints.reach(Point.AFTER_RESERVE, memberId);
        try {
            return issueConfirmService.confirm(memberId, couponId);
        } catch (DataIntegrityViolationException e) {
            // DB 에 이미 이 회원의 발급 행이 있다. 예약을 지우면 Redis 가 실제 발급자를 잊으므로 남겨 둔다.
            throw e;
        } catch (RuntimeException e) {
            // DB 확정 실패: 예약을 되돌린다. 커밋 결과를 모르는 실패(커밋 후 응답 유실)도 여기로 오므로 완전한 보상은 아니다.
            failurePoints.reach(Point.BEFORE_CANCEL, memberId);
            couponIssuedRedisRepository.remove(couponId, memberId);
            throw e;
        }
    }

    // 총수량은 쿠폰 생성 때 Redis 에 넣어 둔다. 없으면 DB 에서 읽어 다시 넣는다.
    private int totalCount(Long couponId) {
        return couponIssuedRedisRepository.getTotalCount(couponId).orElseGet(() -> {
            int totalCount = couponRepository.findById(couponId)
                    .orElseThrow(() -> new CouponNotFoundException(ErrorCode.NOT_FOUND_COUPON))
                    .getTotalCount();
            couponIssuedRedisRepository.setTotalCount(couponId, totalCount);
            return totalCount;
        });
    }
}
