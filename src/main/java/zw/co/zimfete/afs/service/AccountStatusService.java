package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.AssetAccountRepository;
import zw.co.zimfete.afs.repo.ReceiptRepository;

/** Recomputes an account's running totals and threshold / loan-cleared status from its receipts. */
@Service
public class AccountStatusService {
    private final ReceiptRepository receipts;
    private final AssetAccountRepository accounts;

    public AccountStatusService(ReceiptRepository receipts, AssetAccountRepository accounts) {
        this.receipts = receipts;
        this.accounts = accounts;
    }

    public void recalc(AssetAccount a) {
        a.setTotalDeposited(receipts.sumForAccount(a.getId(), ReceiptType.ASSET_DEPOSIT));
        a.setTotalRepaid(receipts.sumForAccount(a.getId(), ReceiptType.LOAN_REPAYMENT));

        if (a.getStatus() == ProjectStatus.SAVING || a.getStatus() == ProjectStatus.THRESHOLD_MET) {
            LocalDate reached = dateThresholdReached(a);
            a.setThresholdReachedDate(reached);
            a.setStatus(reached != null ? ProjectStatus.THRESHOLD_MET : ProjectStatus.SAVING);
        }

        if (a.isLoanStarted()) {
            a.setLoanClearedDate(a.getLoanBalance().signum() == 0 ? lastRepaymentDate(a) : null);
        }
        accounts.save(a);
    }

    private LocalDate dateThresholdReached(AssetAccount a) {
        BigDecimal min = a.getMinimumDeposit();
        if (min == null || min.signum() <= 0) return null;
        BigDecimal running = BigDecimal.ZERO;
        for (Receipt r : receipts.findByAccountIdOrderByReceiptDateAscIdAsc(a.getId())) {
            if (r.isReversed() || r.getType() != ReceiptType.ASSET_DEPOSIT) continue;
            running = running.add(r.getAmount());
            if (running.compareTo(min) >= 0) return r.getReceiptDate();
        }
        return null;
    }

    private LocalDate lastRepaymentDate(AssetAccount a) {
        LocalDate last = null;
        for (Receipt r : receipts.findByAccountIdOrderByReceiptDateAscIdAsc(a.getId())) {
            if (!r.isReversed() && r.getType() == ReceiptType.LOAN_REPAYMENT) last = r.getReceiptDate();
        }
        return last;
    }
}
