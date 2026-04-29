package couponToy.CouponToyProject.CouponIssue.controller;

import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponRequest;
import couponToy.CouponToyProject.CouponIssue.service.EventIssueCouponService;
import couponToy.CouponToyProject.CouponIssue.service.IssueCouponService;
import couponToy.CouponToyProject.CouponIssue.service.PessimisticIssueCouponService;
import couponToy.CouponToyProject.global.api.ApiUtils;
import couponToy.CouponToyProject.global.security.MemberDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
public class IssueCouponController {

    private final IssueCouponService issueCouponService;
    private final PessimisticIssueCouponService pessimisticIssueCouponService;
    private final EventIssueCouponService eventIssueCouponService;

    @PostMapping("issue/{couponId}")
    public ResponseEntity<?> issueCoupon(
            @AuthenticationPrincipal MemberDetails memberDetails,
            @RequestBody(required = false) IssueCouponRequest issueCouponRequest,
            @PathVariable Long couponId) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        ApiUtils.success(
                                issueCouponService.issueCoupon(memberDetails, couponId)
                        )
                );
    }

    @PostMapping("issue/event/{couponId}")
    public ResponseEntity<?> issueCouponWithEvent(
            @AuthenticationPrincipal MemberDetails memberDetails,
            @RequestBody(required = false) IssueCouponRequest issueCouponRequest,
            @PathVariable Long couponId) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        ApiUtils.success(
                                eventIssueCouponService.issueCoupon(memberDetails, couponId)
                        )
                );
    }

    @PostMapping("issue/pessimistic/{couponId}")
    public ResponseEntity<?> issueCouponWithPessimisticLock(
            @AuthenticationPrincipal MemberDetails memberDetails,
            @RequestBody(required = false) IssueCouponRequest issueCouponRequest,
            @PathVariable Long couponId) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        ApiUtils.success(
                                pessimisticIssueCouponService.issueCoupon(memberDetails, couponId)
                        )
                );
    }
}
