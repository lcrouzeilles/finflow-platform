package com.finflow.transaction_service.service;

import com.finflow.transaction_service.domain.account.Owner;
import com.finflow.transaction_service.repository.OwnerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OwnerIdentityServiceIntegrationTest {

    @Autowired
    private OwnerIdentityService ownerIdentityService;

    @Autowired
    private OwnerRepository ownerRepository;

    private static Jwt jwtWithSubject(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    @Test
    void shouldProvisionOwnerForUnknownSubject() {
        String subject = "subject-" + UUID.randomUUID();

        UUID ownerId = ownerIdentityService.resolveOwnerId(
                jwtWithSubject(subject)
        );

        Owner owner = ownerRepository.findById(ownerId).orElseThrow();

        assertThat(owner.getKeycloakSubject()).isEqualTo(subject);
    }

    @Test
    void shouldReturnSameOwnerForSameSubject() {
        String subject = "subject-" + UUID.randomUUID();

        UUID first = ownerIdentityService.resolveOwnerId(
                jwtWithSubject(subject)
        );
        UUID second = ownerIdentityService.resolveOwnerId(
                jwtWithSubject(subject)
        );

        assertThat(second).isEqualTo(first);
        assertThat(ownerRepository.findByKeycloakSubject(subject))
                .isPresent();
    }

    @Test
    void shouldReturnExistingOwnerBoundToSubject() {
        String subject = "subject-" + UUID.randomUUID();

        Owner existing = ownerRepository.saveAndFlush(new Owner(subject));

        UUID resolved = ownerIdentityService.resolveOwnerId(
                jwtWithSubject(subject)
        );

        assertThat(resolved).isEqualTo(existing.getId());
    }

    @Test
    void shouldResolveDifferentOwnersForDifferentSubjects() {
        UUID first = ownerIdentityService.resolveOwnerId(
                jwtWithSubject("subject-" + UUID.randomUUID())
        );
        UUID second = ownerIdentityService.resolveOwnerId(
                jwtWithSubject("subject-" + UUID.randomUUID())
        );

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void shouldRejectMissingAuthentication() {
        assertThatThrownBy(() -> ownerIdentityService.resolveOwnerId(null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void shouldRejectTokenWithoutSubject() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("scope", "read")
                .build();

        assertThatThrownBy(() -> ownerIdentityService.resolveOwnerId(jwt))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }
}
