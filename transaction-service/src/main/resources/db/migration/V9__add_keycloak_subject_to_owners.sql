ALTER TABLE owners
    ADD COLUMN keycloak_subject VARCHAR(255);

CREATE UNIQUE INDEX uk_owners_keycloak_subject
    ON owners (keycloak_subject)
    WHERE keycloak_subject IS NOT NULL;
