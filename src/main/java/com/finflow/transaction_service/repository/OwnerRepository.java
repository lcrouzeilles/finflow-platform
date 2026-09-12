package com.finflow.transaction_service.repository;


import com.finflow.transaction_service.domain.account.Owner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OwnerRepository extends JpaRepository<Owner, UUID> {
}