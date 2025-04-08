package couponToy.CouponToyProject.queue.controller;

import couponToy.CouponToyProject.global.api.ApiUtils;
import couponToy.CouponToyProject.global.security.MemberDetails;
import couponToy.CouponToyProject.queue.service.CouponQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class CouponQueueController {

    private final CouponQueueService couponQueueService;

    /**
     * 쿠폰 발급 요청 → Redis 큐에 등록
     */
    @PostMapping("/coupon/{couponId}")
    public ResponseEntity<?> registerCouponQueue(
            @PathVariable Long couponId,
            @AuthenticationPrincipal MemberDetails memberDetails
    ) {
        Long userId = memberDetails.getMemberId();

        couponQueueService.registerUser(couponId, userId);
        Long rank = couponQueueService.getUserRank(couponId, userId);

        return ResponseEntity.ok(
                ApiUtils.success("쿠폰 발급 요청 완료. 현재 대기 순번: " + (rank + 1))
        );
    }

    /**
     * 사용자 발급 여부 확인
     */
    @GetMapping("/coupon/{couponId}/status")
    public ResponseEntity<?> checkIssued(
            @PathVariable Long couponId,
            @AuthenticationPrincipal MemberDetails memberDetails
    ) {
        Long userId = memberDetails.getMemberId();

        boolean issued = couponQueueService.isAlreadyIssued(couponId, userId);
        return ResponseEntity.ok(
                ApiUtils.success(issued ? "쿠폰 발급 완료됨" : "아직 미발급")
        );
    }
}
