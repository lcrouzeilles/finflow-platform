package com.finflow.transaction_service.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.Map;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

public class KeycloakRoleConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");

        if (realmAccess == null) {
            return new JwtAuthenticationToken(jwt, emptyList());
        }

        Object roles = realmAccess.get("roles");

        if (!(roles instanceof Collection<?> roleCollection)) {
            return new JwtAuthenticationToken(jwt, emptyList());
        }

        var authorities = roleCollection.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(toList());

        return new JwtAuthenticationToken(jwt, authorities);
    }
}