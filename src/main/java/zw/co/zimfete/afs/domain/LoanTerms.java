package zw.co.zimfete.afs.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Asset finance loan per company policy:
 * loan principal = quotation cost - amount deposited;
 * interest = 30% of principal, charged once;
 * total repayable = principal + interest, spread evenly over the agreed months.
 */
public record LoanTerms(BigDecimal principal, BigDecimal interest, BigDecimal totalRepayable,
                        Integer months, BigDecimal monthlyInstalment) {

    public static final BigDecimal INTEREST_RATE = new BigDecimal("0.30");

    public static LoanTerms calculate(BigDecimal quotationCost, BigDecimal deposited, Integer months) {
        BigDecimal principal = quotationCost.subtract(deposited == null ? BigDecimal.ZERO : deposited).max(BigDecimal.ZERO);
        return fromPrincipal(principal, months);
    }

    public static LoanTerms fromPrincipal(BigDecimal principal, Integer months) {
        BigDecimal p = principal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal interest = p.multiply(INTEREST_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = p.add(interest);
        BigDecimal monthly = months != null && months > 0
                ? total.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP)
                : total;
        return new LoanTerms(p, interest, total, months, monthly);
    }
}
