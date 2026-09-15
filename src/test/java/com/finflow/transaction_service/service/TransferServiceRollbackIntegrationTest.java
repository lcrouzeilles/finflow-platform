package com.finflow.transaction_service.service;

import com.finflow.transaction_service.repository.AccountRepository;
import com.finflow.transaction_service.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class TransferServiceRollbackIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @MockitoBean
    private TransactionRepository mockedTransactionRepository;

    @Autowired
    private TransferService transferService;
}
