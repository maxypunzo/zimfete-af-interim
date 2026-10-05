package zw.co.zimfete.afs;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import zw.co.zimfete.afs.domain.LoanTerms;

class LoanTermsTest {

    @Test
    void loanIsCostLessDepositPlusInterestOnce() {
        // $5,000 borehole, $2,500 deposited (50%): 2,500 + 30% = 3,250 over 10 months
        LoanTerms t = LoanTerms.calculate(new BigDecimal("5000"), new BigDecimal("2500"), 10, new BigDecimal("30"));
        assertThat(t.principal()).isEqualByComparingTo("2500.00");
        assertThat(t.interest()).isEqualByComparingTo("750.00");
        assertThat(t.totalRepayable()).isEqualByComparingTo("3250.00");
        assertThat(t.monthlyInstalment()).isEqualByComparingTo("325.00");
    }

    @Test
    void matchesTheOldRepaymentsSheetAtTwentyPercent() {
        // Cephas Chari: project 1,825, deposited 960 -> loan 865, grand total 1,038
        LoanTerms t = LoanTerms.calculate(new BigDecimal("1825"), new BigDecimal("960"), 4, new BigDecimal("20"));
        assertThat(t.principal()).isEqualByComparingTo("865.00");
        assertThat(t.totalRepayable()).isEqualByComparingTo("1038.00");
    }

    @Test
    void depositAboveCostMeansNoLoan() {
        LoanTerms t = LoanTerms.calculate(new BigDecimal("1000"), new BigDecimal("1200"), 6, null);
        assertThat(t.totalRepayable()).isEqualByComparingTo("0");
    }
}
