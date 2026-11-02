package com.finflow.transaction_service.payment.reconciliation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationConfig {
}
