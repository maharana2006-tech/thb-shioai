package com.multiship.backend.config;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The authenticated caller behind an API key. Carries the client the key ships
 * for and its scopes, so external endpoints can attribute shipments and enforce
 * per-client access. Authority is {@code ROLE_API}.
 */
public class ApiKeyPrincipal implements UserDetails {

    private final Long apiKeyId;
    private final String keyName;
    private final String clientCode;
    private final Set<String> scopes;

    public ApiKeyPrincipal(Long apiKeyId, String keyName, String clientCode, Set<String> scopes) {
        this.apiKeyId = apiKeyId;
        this.keyName = keyName;
        this.clientCode = clientCode;
        this.scopes = scopes;
    }

    public Long getApiKeyId() { return apiKeyId; }
    public String getKeyName() { return keyName; }
    public String getClientCode() { return clientCode; }
    public Set<String> getScopes() { return scopes; }
    public boolean hasScope(String scope) { return scopes.contains(scope) || scopes.contains("*"); }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_API"));
    }

    @Override public String getPassword() { return ""; }
    /** The "username" is the client code — used wherever a caller identity is needed. */
    @Override public String getUsername() { return clientCode; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
