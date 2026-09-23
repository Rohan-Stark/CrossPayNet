CREATE TABLE banking.customer (
    customer_id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE banking.account (
    account_id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES banking.customer(customer_id),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    available_balance NUMERIC(19,4) NOT NULL,
    reserved_amount NUMERIC(19,4) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE banking.fund_reservation (
    reservation_token UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES banking.account(account_id),
    payment_reference VARCHAR(255) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Protect against overlapping payment references for the same account
CREATE UNIQUE INDEX idx_fund_reservation_acc_ref ON banking.fund_reservation(account_id, payment_reference);
