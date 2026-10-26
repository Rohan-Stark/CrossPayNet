package com.crosspaynet.compliance.domain;

import com.crosspaynet.common.money.Money;
import com.crosspaynet.payment.contracts.PaymentInstruction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure domain screening engine.
 *
 * <p><strong>Important Disclaimer:</strong> These are simulated screening watchlists and educational
 * threshold policies only, not real-world sanctions lists or live regulatory data.
 */
public class SanctionsMatcher {

    public static final String FLAG_NAME_MATCH = "SANCTION_WATCHLIST_NAME_MATCH";
    public static final String FLAG_BIC_MATCH = "SANCTION_WATCHLIST_BIC_MATCH";
    public static final String FLAG_HIGH_VALUE = "HIGH_VALUE_TRANSACTION_THRESHOLD";

    private static final Set<String> DEFAULT_WATCHLIST_KEYWORDS = Set.of(
            "NORTH KOREA",
            "TERROR",
            "SANCTIONED",
            "BLOCKED ENTITY",
            "CRIMINAL ENTERPRISE"
    );

    private static final Set<String> DEFAULT_WATCHLIST_BICS = Set.of(
            "PRKOPKPY",
            "BLCKUS33",
            "SANCTEST"
    );

    private static final BigDecimal DEFAULT_USD_THRESHOLD = new BigDecimal("10000.0000");

    private final Set<String> watchlistKeywords;
    private final Set<String> watchlistBics;
    private final Map<String, BigDecimal> currencyThresholds;

    public SanctionsMatcher() {
        this(DEFAULT_WATCHLIST_KEYWORDS, DEFAULT_WATCHLIST_BICS, Map.of("USD", DEFAULT_USD_THRESHOLD));
    }

    public SanctionsMatcher(
            Set<String> watchlistKeywords,
            Set<String> watchlistBics,
            Map<String, BigDecimal> currencyThresholds
    ) {
        this.watchlistKeywords = watchlistKeywords != null ? Set.copyOf(watchlistKeywords) : Collections.emptySet();
        this.watchlistBics = watchlistBics != null ? Set.copyOf(watchlistBics) : Collections.emptySet();
        this.currencyThresholds = currencyThresholds != null ? new HashMap<>(currencyThresholds) : new HashMap<>();
    }

    public ScreeningResult screen(PaymentInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction cannot be null.");

        List<String> flagReasons = new ArrayList<>();
        int riskScore = 0;

        // 1. Beneficiary name watchlist check (case-insensitive contains)
        String beneficiaryName = instruction.beneficiary().beneficiaryName();
        if (beneficiaryName != null) {
            String upperName = beneficiaryName.toUpperCase();
            for (String keyword : watchlistKeywords) {
                if (upperName.contains(keyword.toUpperCase())) {
                    flagReasons.add(FLAG_NAME_MATCH + ": " + keyword);
                    riskScore += 50;
                    break;
                }
            }
        }

        // 2. Destination BIC watchlist check (normalized prefix / exact)
        String bic = instruction.beneficiary().beneficiaryBankBic().getValue();
        if (bic != null) {
            String upperBic = bic.toUpperCase();
            for (String watchBic : watchlistBics) {
                if (upperBic.startsWith(watchBic.toUpperCase()) || watchBic.toUpperCase().startsWith(upperBic)) {
                    flagReasons.add(FLAG_BIC_MATCH + ": " + watchBic);
                    riskScore += 50;
                    break;
                }
            }
        }

        // 3. Currency-specific high-value threshold check (no implicit FX conversions)
        Money amount = instruction.amount();
        String ccy = amount.getCurrency().getCode();
        if (currencyThresholds.containsKey(ccy)) {
            BigDecimal threshold = currencyThresholds.get(ccy);
            if (amount.getAmount().compareTo(threshold) > 0) {
                flagReasons.add(FLAG_HIGH_VALUE + ": amount " + amount.getAmount() + " > " + threshold + " " + ccy);
                riskScore += 30;
            }
        }

        riskScore = Math.min(riskScore, 100);
        return new ScreeningResult(riskScore, Collections.unmodifiableList(flagReasons));
    }

    public record ScreeningResult(int riskScore, List<String> flagReasons) {
        public boolean hasFlags() {
            return !flagReasons.isEmpty();
        }
    }
}
