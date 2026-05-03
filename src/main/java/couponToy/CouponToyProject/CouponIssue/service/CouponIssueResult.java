package couponToy.CouponToyProject.CouponIssue.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CouponIssueResult {
    SUCCESS("success"),
    ACCEPTED("accepted"),
    DUPLICATE("duplicate"),
    SOLD_OUT("sold_out"),
    ERROR("error");

    private final String tag;
}
