package couponToy.CouponToyProject.Member.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class MemberLogInRequest {
    private String email;
    private String password;
}
