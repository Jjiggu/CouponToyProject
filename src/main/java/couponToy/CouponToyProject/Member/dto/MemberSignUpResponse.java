package couponToy.CouponToyProject.Member.dto;

import couponToy.CouponToyProject.Member.model.Member;
import couponToy.CouponToyProject.global.constant.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MemberSignUpResponse {
    private Integer memberId;
    private String email;
    private String password;
    private String name;
    private String role;

    public static MemberSignUpResponse fromEntity(Member member) {
        return MemberSignUpResponse.builder()
                .memberId(member.getMemberId())
                .email(member.getEmail())
                .password(member.getPassword())
                .name(member.getName())
                .role(member.getRole().toString())
                .build();
    }

}
