package com.cixingji.backend.service.impl.user;

import com.cixingji.backend.pojo.entity.User;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

@Data
@NoArgsConstructor
public class UserDetailsImpl implements UserDetails {

    private User user;
    private String scope = "user";

    public UserDetailsImpl(User user) {
        this(user, "user");
    }

    public UserDetailsImpl(User user, String scope) {
        this.user = user;
        this.scope = scope;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if ("admin".equals(scope) && user != null) {
            if (Integer.valueOf(2).equals(user.getRole())) {
                return Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
            }
            if (Integer.valueOf(1).equals(user.getRole())) {
                return Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"));
            }
        }
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return user != null && !Integer.valueOf(1).equals(user.getState());
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user != null && Integer.valueOf(0).equals(user.getState());
    }
}
