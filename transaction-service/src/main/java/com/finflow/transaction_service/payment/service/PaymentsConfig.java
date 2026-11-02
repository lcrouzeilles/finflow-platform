package com.finflow.transaction_service.payment.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PaymentsProperties.class)
public class PaymentsConfig {
}
