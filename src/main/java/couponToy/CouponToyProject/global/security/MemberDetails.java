package couponToy.CouponToyProject.global.security;

import couponToy.CouponToyProject.Member.model.Member;
import lombok.Builder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Builder
public class MemberDetails implements UserDetails {
    private final Long id;
    private final String email;
    private final String password;
    private final Collection<? extends GrantedAuthority> authorities;


    public static MemberDetails create (Member member) {
        return MemberDetails.builder()
                .id(member.getMemberId())
                .email(member.getEmail())
                .password(member.getPassword())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + member.getRole().name())))
                .build();
    }


    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String getPassword() {
        return password;
    }

}
