package com.finflow.transaction_service.domain.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.util.UUID;

@Entity
@Table(name = "owners")
@Getter
public class Owner {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Identity-provider subject ({@code sub} claim of the Keycloak JWT)
     * this owner is bound to. Nullable so owners can also exist without
     * an identity (legacy rows, test fixtures).
     */
    @Column(name = "keycloak_subject", length = 255)
    private String keycloakSubject;

    public Owner() {
        // Required by JPA
    }

    public Owner(String keycloakSubject) {
        if (keycloakSubject == null || keycloakSubject.isBlank()) {
            throw new IllegalArgumentException(
                    "Keycloak subject cannot be null or blank"
            );
        }
        this.keycloakSubject = keycloakSubject;
    }
}
