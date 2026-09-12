package com.finflow.transaction_service.domain.account;

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

    public Owner() {
        // Required by JPA
    }
}
