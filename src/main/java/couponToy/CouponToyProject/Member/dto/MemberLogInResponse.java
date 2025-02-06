package couponToy.CouponToyProject.Member.dto;

import couponToy.CouponToyProject.Member.model.Member;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Builder
public class MemberLogInResponse {

    private Long memberId;
    private String email;
    private String password;
    private String name;
    private String accessToken;


    public static MemberLogInResponse fromEntity(Member member, String token) {
        return MemberLogInResponse.builder()
                .memberId(member.getMemberId())
                .email(member.getEmail())
                .password(member.getPassword())
                .name(member.getName())
                .accessToken(token)
                .build();
    }
}
