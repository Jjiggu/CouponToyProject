package couponToy.CouponToyProject.WriteBehind.controller;

import couponToy.CouponToyProject.WriteBehind.service.WriteBehindLoader;
import couponToy.CouponToyProject.global.api.ApiUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 9단계 대사(Redis 명단과 DB 행 맞추기). 실험 API 와 같이 local 프로필에서만, 인증 없이 연다.
 */
@Profile("local")
@RestController
@RequiredArgsConstructor
public class WriteBehindController {

    private final WriteBehindLoader loader;

    @PostMapping("/lab/write-behind/coupons/{couponId}/reconcile")
    public ResponseEntity<?> reconcile(@PathVariable Long couponId) {
        return ResponseEntity.ok(ApiUtils.success(loader.reconcile(couponId)));
    }
}
