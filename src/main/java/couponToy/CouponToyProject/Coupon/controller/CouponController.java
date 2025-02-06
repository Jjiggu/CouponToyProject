package couponToy.CouponToyProject.Coupon.controller;

import couponToy.CouponToyProject.Coupon.dto.CouponCreateRequest;
import couponToy.CouponToyProject.Coupon.service.CouponService;
import couponToy.CouponToyProject.global.api.ApiUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @PostMapping
    public ResponseEntity<?> createCoupon(@RequestBody @Valid CouponCreateRequest couponCreateRequest) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        ApiUtils.success(
                                couponService.createCoupon(couponCreateRequest)
                        )
                );
    }

    @GetMapping("{couponId}")
    public ResponseEntity<?> getCouponById(@PathVariable Long couponId) {
        return ResponseEntity
                .ok()
                .body(
                        ApiUtils.success(
                                couponService.findCouponById(couponId)
                        )
                );
    }

}
