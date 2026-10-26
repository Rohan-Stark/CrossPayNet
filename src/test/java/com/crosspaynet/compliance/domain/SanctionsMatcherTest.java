package com.crosspaynet.compliance.domain;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentInstruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SanctionsMatcherTest {

    private SanctionsMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new SanctionsMatcher();
    }

    @Test
    void shouldPassCleanInstructionWithZeroRiskScoreAndNoFlags() {
        PaymentInstruction cleanInstruction = createInstruction(
                "Acme Global Supplies Corp",
                "CHASUS33XXX",
                Money.of("5000.0000", "USD")
        );

        SanctionsMatcher.ScreeningResult result = matcher.screen(cleanInstruction);

        assertThat(result.hasFlags()).isFalse();
        assertThat(result.riskScore()).isEqualTo(0);
        assertThat(result.flagReasons()).isEmpty();
    }

    @Test
    void shouldFlagSimulatedSanctionsKeywordInBeneficiaryNameCaseInsensitive() {
        PaymentInstruction flaggedName = createInstruction(
                "Global Terror Logistics LLC",
                "CHASUS33XXX",
                Money.of("1000.0000", "USD")
        );

        SanctionsMatcher.ScreeningResult result = matcher.screen(flaggedName);

        assertThat(result.hasFlags()).isTrue();
        assertThat(result.riskScore()).isGreaterThanOrEqualTo(50);
        assertThat(result.flagReasons()).anyMatch(reason -> reason.contains(SanctionsMatcher.FLAG_NAME_MATCH));
    }

    @Test
    void shouldFlagSimulatedSanctionsWatchlistBic() {
        PaymentInstruction flaggedBic = createInstruction(
                "Ordinary Merchant Ltd",
                "PRKOPKPYXXX",
                Money.of("500.0000", "USD")
        );

        SanctionsMatcher.ScreeningResult result = matcher.screen(flaggedBic);

        assertThat(result.hasFlags()).isTrue();
        assertThat(result.riskScore()).isGreaterThanOrEqualTo(50);
        assertThat(result.flagReasons()).anyMatch(reason -> reason.contains(SanctionsMatcher.FLAG_BIC_MATCH));
    }

    @Test
    void shouldFlagUsdAmountAboveThreshold() {
        PaymentInstruction highValueUsd = createInstruction(
                "Ordinary Merchant Ltd",
                "CHASUS33XXX",
                Money.of("10000.0100", "USD")
        );

        SanctionsMatcher.ScreeningResult result = matcher.screen(highValueUsd);

        assertThat(result.hasFlags()).isTrue();
        assertThat(result.riskScore()).isEqualTo(30);
        assertThat(result.flagReasons()).anyMatch(reason -> reason.contains(SanctionsMatcher.FLAG_HIGH_VALUE));
    }

    @Test
    void shouldNotFlagNonUsdCurrencyAgainstUsdThresholdWithoutExplicitThreshold() {
        // 50,000 EUR should not be compared against USD 10,000 threshold or implicitly converted
        PaymentInstruction highValueEur = createInstruction(
                "European Merchant GmbH",
                "DEUTDEFFXXX",
                Money.of("50000.0000", "EUR")
        );

        SanctionsMatcher.ScreeningResult result = matcher.screen(highValueEur);

        assertThat(result.hasFlags()).isFalse();
        assertThat(result.riskScore()).isEqualTo(0);
    }

    @Test
    void shouldSupportConfigurableCurrencyThresholds() {
        SanctionsMatcher customMatcher = new SanctionsMatcher(
                Set.of("SANCTIONED"),
                Set.of("SANCTEST"),
                Map.of("EUR", new BigDecimal("25000.0000"))
        );

        PaymentInstruction belowEur = createInstruction("Clean Entity", "DEUTDEFFXXX", Money.of("20000.0000", "EUR"));
        PaymentInstruction aboveEur = createInstruction("Clean Entity", "DEUTDEFFXXX", Money.of("30000.0000", "EUR"));

        assertThat(customMatcher.screen(belowEur).hasFlags()).isFalse();
        assertThat(customMatcher.screen(aboveEur).hasFlags()).isTrue();
        assertThat(customMatcher.screen(aboveEur).flagReasons()).anyMatch(r -> r.contains(SanctionsMatcher.FLAG_HIGH_VALUE));
    }

    @Test
    void shouldAggregateMultipleFlagsAndCapRiskScoreAt100() {
        PaymentInstruction multiFlagged = createInstruction(
                "Sanctioned Terror Group",
                "PRKOPKPYXXX",
                Money.of("25000.0000", "USD")
        );

        SanctionsMatcher.ScreeningResult result = matcher.screen(multiFlagged);

        assertThat(result.hasFlags()).isTrue();
        assertThat(result.riskScore()).isEqualTo(100);
        assertThat(result.flagReasons().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldRejectNullInstruction() {
        assertThatThrownBy(() -> matcher.screen(null))
                .isInstanceOf(NullPointerException.class);
    }

    private PaymentInstruction createInstruction(String beneficiaryName, String bic, Money amount) {
        return new PaymentInstruction(
                AccountId.generate(),
                PaymentReference.of("REF-" + UUID.randomUUID().toString().substring(0, 8)),
                new BeneficiaryDetails(beneficiaryName, "ACC-123456", BicCode.of(bic)),
                amount
        );
    }
}
