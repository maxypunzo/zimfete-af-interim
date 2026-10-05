package zw.co.zimfete.afs.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import zw.co.zimfete.afs.domain.LoanTerms;

@ConfigurationProperties(prefix = "afs")
public record AfsProperties(Login login, String officerName, BigDecimal defaultInterestPercent) {
    public record Login(String username, String password) {
    }

    /** Interest charged once on asset finance loans unless a project says otherwise. */
    public BigDecimal interestPercent() {
        return defaultInterestPercent != null ? defaultInterestPercent : LoanTerms.DEFAULT_INTEREST_PERCENT;
    }
}
