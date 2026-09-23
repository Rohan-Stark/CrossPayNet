package com.crosspaynet.banking.infrastructure.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "account", schema = "banking")
public class AccountEntity {

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "available_balance", precision = 19, scale = 4, nullable = false)
    private BigDecimal availableBalance;

    @Column(name = "reserved_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal reservedAmount;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(mappedBy = "account", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FundReservationEntity> reservations = new ArrayList<>();

    protected AccountEntity() {}

    public AccountEntity(UUID accountId, UUID customerId, String currency, String status, 
                         BigDecimal availableBalance, BigDecimal reservedAmount) {
        this.accountId = accountId;
        this.customerId = customerId;
        this.currency = currency;
        this.status = status;
        this.availableBalance = availableBalance;
        this.reservedAmount = reservedAmount;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getCustomerId() {
        return customerId;
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

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public void setAvailableBalance(BigDecimal availableBalance) {
        this.availableBalance = availableBalance;
    }

    public BigDecimal getReservedAmount() {
        return reservedAmount;
    }

    public void setReservedAmount(BigDecimal reservedAmount) {
        this.reservedAmount = reservedAmount;
    }

    public Long getVersion() {
        return version;
    }

    public List<FundReservationEntity> getReservations() {
        return reservations;
    }

    public void addReservation(FundReservationEntity reservation) {
        reservations.add(reservation);
        reservation.setAccount(this);
    }
}
