package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.repository.OwnerRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Maps the authenticated identity (the {@code sub} claim of the JWT issued
 * by Keycloak) to the application-level {@link Owner}.
 *
 * <p>Owners are provisioned lazily: the first authenticated request from a
 * subject creates its {@link Owner} row. Concurrent first requests for the
 * same subject are resolved by the unique index on
 * {@code owners.keycloak_subject}.
 */
@Service
public class OwnerIdentityService {

    private final OwnerRepository ownerRepository;

    public OwnerIdentityService(OwnerRepository ownerRepository) {
        this.ownerRepository = ownerRepository;
    }

    public UUID resolveOwnerId(Jwt jwt) {
        if (jwt == null) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Authentication is required"
            );
        }

        String subject = jwt.getSubject();

        if (subject == null || subject.isBlank()) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Authenticated token has no subject"
            );
        }

        return ownerRepository.findByKeycloakSubject(subject)
                .map(Owner::getId)
                .orElseGet(() -> provisionOwner(subject));
    }

    private UUID provisionOwner(String subject) {
        try {
            return ownerRepository.saveAndFlush(new Owner(subject)).getId();
        } catch (DataIntegrityViolationException exception) {
            // Another request provisioned the same subject first.
            return ownerRepository.findByKeycloakSubject(subject)
                    .map(Owner::getId)
                    .orElseThrow(() -> exception);
        }
    }
}
