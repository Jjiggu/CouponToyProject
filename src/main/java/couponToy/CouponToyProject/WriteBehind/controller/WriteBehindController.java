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
 * 적재기는 쿠폰 행을 갱신하지 않으므로, issued_count 는 이 API 를 부른 뒤에야 실제 발급 행 수와 같아진다.
 * 응답은 WriteBehindLoader.Reconciliation (넣은 회원, DB 에만 있는 회원, 맞춘 issued_count, 초과 발급 여부)
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
