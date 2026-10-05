package zw.co.zimfete.afs;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import zw.co.zimfete.afs.domain.LoanTerms;

class LoanTermsTest {

    @Test
    void loanIsQuotationLessDepositPlusThirtyPercentOnce() {
        // $5,000 borehole, client deposited $2,500 (50%): loan 2,500 + 750 = 3,250 over 10 months
        LoanTerms t = LoanTerms.calculate(new BigDecimal("5000"), new BigDecimal("2500"), 10);
        assertThat(t.principal()).isEqualByComparingTo("2500.00");
        assertThat(t.interest()).isEqualByComparingTo("750.00");
        assertThat(t.totalRepayable()).isEqualByComparingTo("3250.00");
        assertThat(t.monthlyInstalment()).isEqualByComparingTo("325.00");
    }

    @Test
    void depositAboveQuotationMeansNoLoan() {
        LoanTerms t = LoanTerms.calculate(new BigDecimal("1000"), new BigDecimal("1200"), 6);
        assertThat(t.totalRepayable()).isEqualByComparingTo("0");
    }
}
