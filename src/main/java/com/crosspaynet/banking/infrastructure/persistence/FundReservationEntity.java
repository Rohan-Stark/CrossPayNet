package com.crosspaynet.banking.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "fund_reservation", schema = "banking")
public class FundReservationEntity {

    @Id
    @Column(name = "reservation_token", nullable = false)
    private UUID reservationToken;

    @ManyToOne(optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private AccountEntity account;

    @Column(name = "payment_reference", nullable = false)
    private String paymentReference;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    protected FundReservationEntity() {}

    public FundReservationEntity(UUID reservationToken, AccountEntity account, String paymentReference, 
                                 BigDecimal amount, String currency, String status) {
        this.reservationToken = reservationToken;
        this.account = account;
        this.paymentReference = paymentReference;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
    }

    public UUID getReservationToken() {
        return reservationToken;
    }

    public AccountEntity getAccount() {
        return account;
    }
    
    public void setAccount(AccountEntity account) {
        this.account = account;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
