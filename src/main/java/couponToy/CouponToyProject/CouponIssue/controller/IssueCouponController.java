package couponToy.CouponToyProject.CouponIssue.controller;

import couponToy.CouponToyProject.CouponIssue.dto.IssueCouponRequest;
import couponToy.CouponToyProject.CouponIssue.service.IssueCouponService;
import couponToy.CouponToyProject.global.api.ApiUtils;
import couponToy.CouponToyProject.global.security.MemberDetails;
import jakarta.validation.Valid;
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

    @PostMapping("issue/{couponId}")
    public ResponseEntity<?> issueCoupon(
            @AuthenticationPrincipal MemberDetails memberDetails,
            @RequestBody @Valid IssueCouponRequest issueCouponRequest,
            @PathVariable Long couponId) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        ApiUtils.success(
                                issueCouponService.issueCoupon(issueCouponRequest, memberDetails, couponId)
                        )
                );
    }
}
