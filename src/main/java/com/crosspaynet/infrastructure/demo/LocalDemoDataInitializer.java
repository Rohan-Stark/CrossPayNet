package com.crosspaynet.infrastructure.demo;

import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.CustomerAccountView;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.routing.RoutingService;
import com.crosspaynet.routing.contracts.RegisterCorrespondentRouteCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Initializes local demonstration data for frontend and simulation developers.
 *
 * <p>Strictly active only under the {@code local} profile when {@code crosspaynet.demo-data.enabled=true}.
 * Ensures complete idempotency: does not overwrite existing account balances or duplicate
 * correspondent routes on successive restarts.</p>
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "crosspaynet.demo-data.enabled", havingValue = "true")
public class LocalDemoDataInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(LocalDemoDataInitializer.class);

    private final BankingService bankingService;
    private final RoutingService routingService;
    private final String customerUsername;

    public LocalDemoDataInitializer(
            BankingService bankingService,
            RoutingService routingService,
            @Value("${crosspaynet.security.users.customer.username:customer}") String customerUsername
    ) {
        this.bankingService = Objects.requireNonNull(bankingService, "bankingService cannot be null.");
        this.routingService = Objects.requireNonNull(routingService, "routingService cannot be null.");
        this.customerUsername = customerUsername;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        initializeDemoData();
    }

    /**
     * Seeds the demo customer, a funded USD account, and a correspondent route idempotently.
     */
    public void initializeDemoData() {
        log.info("Checking local demo data initialization for customer '{}'...", customerUsername);

        UUID customerId;
        try {
            customerId = UUID.fromString(customerUsername);
        } catch (IllegalArgumentException ex) {
            customerId = UUID.nameUUIDFromBytes(customerUsername.getBytes(StandardCharsets.UTF_8));
        }

        bankingService.createCustomerIfNotExists(customerId);

        List<CustomerAccountView> accounts = bankingService.findAccountsByCustomer(customerId);
        boolean hasUsdAccount = accounts.stream().anyMatch(a -> "USD".equals(a.currency().getCode()));
        if (!hasUsdAccount) {
            AccountId accountId = bankingService.openAccount(
                    customerId,
                    new Currency("USD"),
                    new Money(new BigDecimal("10000.0000"), new Currency("USD"))
            );
            log.info("Initialized demo USD account: accountId={}, customerId={}, balance=10000.0000 USD",
                    accountId.getValue(), customerId);
        } else {
            log.info("Demo USD account already exists for customerId={}", customerId);
        }

        try {
            routingService.registerRoute(new RegisterCorrespondentRouteCommand(
                    "ROUTE-LOCAL-DEUT-USD",
                    new BicCode("DEUTDEFFXXX"),
                    new BicCode("CPAYUS33XXX"),
                    new Currency("USD"),
                    "NOSTRO-DEUT-USD-001",
                    1
            ));
            log.info("Initialized demo correspondent route: ROUTE-LOCAL-DEUT-USD (CPAYUS33XXX -> DEUTDEFFXXX)");
        } catch (Exception ex) {
            log.info("Demo correspondent route already registered or retained: {}", ex.getMessage());
        }
    }
}
