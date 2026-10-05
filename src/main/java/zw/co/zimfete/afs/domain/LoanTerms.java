package zw.co.zimfete.afs.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Asset finance loan per company policy:
 * loan principal = quotation cost - amount deposited;
 * interest = rate% of principal, charged once (policy 30%; set per project);
 * total repayable = principal + interest, spread evenly over the agreed months.
 */
public record LoanTerms(BigDecimal principal, BigDecimal interest, BigDecimal totalRepayable,
                        Integer months, BigDecimal monthlyInstalment, BigDecimal ratePercent) {

    public static final BigDecimal DEFAULT_INTEREST_PERCENT = new BigDecimal("30");

    public static LoanTerms calculate(BigDecimal quotationCost, BigDecimal deposited, Integer months, BigDecimal ratePercent) {
        BigDecimal principal = quotationCost.subtract(deposited == null ? BigDecimal.ZERO : deposited).max(BigDecimal.ZERO);
        return fromPrincipal(principal, months, ratePercent);
    }

    public static LoanTerms fromPrincipal(BigDecimal principal, Integer months, BigDecimal ratePercent) {
        BigDecimal rate = ratePercent != null ? ratePercent : DEFAULT_INTEREST_PERCENT;
        BigDecimal p = principal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal interest = p.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal total = p.add(interest);
        BigDecimal monthly = months != null && months > 0
                ? total.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP)
                : total;
        return new LoanTerms(p, interest, total, months, monthly, rate);
    }
}
