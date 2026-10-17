package couponToy.CouponToyProject.Lab.controller;

import couponToy.CouponToyProject.Coupon.dto.CouponCreateRequest;
import couponToy.CouponToyProject.CouponIssue.service.CouponIssueStrategy;
import couponToy.CouponToyProject.Lab.service.LabService;
import couponToy.CouponToyProject.global.api.ApiUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 로컬 학습용 실험 API. 인증이 없으므로 local 프로필에서만 등록된다.
 */
@Profile("local")
@RestController
@RequestMapping("/lab")
@RequiredArgsConstructor
public class LabController {

    private final LabService labService;

    public record CreateMembersRequest(@NotNull @Positive @Max(200_000) Integer count) {
    }

    public record IssueRequest(@NotNull @Positive Long memberId, @NotNull CouponIssueStrategy method) {
    }

    @PostMapping("/coupons")
    public ResponseEntity<?> createCoupon(@RequestBody @Valid CouponCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiUtils.success(labService.createCoupon(request)));
    }

    @PostMapping("/members")
    public ResponseEntity<?> createMembers(@RequestBody @Valid CreateMembersRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiUtils.success(labService.createMembers(request.count())));
    }

    @PostMapping("/coupons/{couponId}/issue")
    public ResponseEntity<?> issue(@PathVariable Long couponId, @RequestBody @Valid IssueRequest request) {
        // 비동기 방식은 접수만 된 것이므로 202. 발급 완료로 집계하지 않는다.
        HttpStatus status = request.method() == CouponIssueStrategy.REDIS_EVENT ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .body(ApiUtils.success(labService.issue(couponId, request.memberId(), request.method())));
    }

    @GetMapping("/coupons/{couponId}/result")
    public ResponseEntity<?> result(@PathVariable Long couponId) {
        return ResponseEntity.ok(ApiUtils.success(labService.result(couponId)));
    }

    @DeleteMapping("/data")
    public ResponseEntity<?> reset() {
        labService.reset();
        return ResponseEntity.ok(ApiUtils.success("reset"));
    }
}
