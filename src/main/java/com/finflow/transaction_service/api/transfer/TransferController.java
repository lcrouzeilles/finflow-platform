package com.finflow.transaction_service.api.transfer;

import com.finflow.transaction_service.domain.transaction.Transaction;
import com.finflow.transaction_service.domain.transaction.TransferRequest;
import com.finflow.transaction_service.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse transfer(
            @Valid @RequestBody CreateTransferRequest request
    ) {
        Transaction transaction = transferService.transfer(
                new TransferRequest(
                        request.sourceAccountId(),
                        request.destinationAccountId(),
                        request.amount()
                )
        );

        return new TransferResponse(
                transaction.getId(),
                transaction.getSourceAccountId(),
                transaction.getDestinationAccountId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus().name()
        );
    }
}
