package com.crosspaynet.banking;

import com.crosspaynet.banking.domain.Account;
import com.crosspaynet.banking.domain.AccountId;
import com.crosspaynet.banking.domain.PaymentReference;
import com.crosspaynet.banking.domain.ReservationToken;
import com.crosspaynet.banking.infrastructure.persistence.AccountEntity;
import com.crosspaynet.banking.infrastructure.persistence.AccountMapper;
import com.crosspaynet.banking.infrastructure.persistence.AccountRepository;
import com.crosspaynet.common.money.Money;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankingService {

    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;

    public BankingService(AccountRepository accountRepository, ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Reserves funds synchronously. Participates in the caller's transaction context.
     * The caller (e.g., Payment module) is responsible for controlling transaction-wide retry semantics.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public ReservationToken reserveFunds(AccountId accountId, Money amount, PaymentReference paymentReference) {
        AccountEntity entity = accountRepository.findById(accountId.getValue())
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));

        Account domainAccount = AccountMapper.toDomain(entity);
        ReservationToken token = domainAccount.reserveFunds(amount, paymentReference);
        
        AccountMapper.updateEntity(domainAccount, entity);

        accountRepository.save(entity);

        eventPublisher.publishEvent(new FundsReserved(
                accountId.getValue(), 
                token.getValue(), 
                paymentReference.getValue(), 
                amount
        ));

        return token;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void fundAccount(AccountId accountId, Money amount) {
        AccountEntity entity = accountRepository.findById(accountId.getValue())
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));

        Account domainAccount = AccountMapper.toDomain(entity);
        domainAccount.fundSimulator(amount);

        AccountMapper.updateEntity(domainAccount, entity);
        accountRepository.save(entity);
    }
}
