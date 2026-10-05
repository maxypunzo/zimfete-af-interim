package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.*;

/**
 * Keeps derived figures in step with the receipts: a client's joining fee / subscription position, an
 * account's opening fee and active status, a project's deposits, threshold date and loan position.
 */
@Service
public class StatusService {
    private final ReceiptRepository receipts;
    private final ClientRepository clients;
    private final AssetAccountRepository accounts;
    private final ProjectRepository projects;

    public StatusService(ReceiptRepository receipts, ClientRepository clients, AssetAccountRepository accounts,
                         ProjectRepository projects) {
        this.receipts = receipts;
        this.clients = clients;
        this.accounts = accounts;
        this.projects = projects;
    }

    public void recalcClient(Client c) {
        c.setJoiningFeePaid(receipts.countJoiningFees(c.getId()) > 0);
        long months = receipts.sumSubscriptionMonths(c.getId());
        LocalDate start = c.getMemberSince() != null ? c.getMemberSince() : c.getDateRegistered();
        c.setSubsPaidUntil(months > 0 ? start.withDayOfMonth(1).plusMonths(months - 1) : null);
        clients.save(c);
    }

    public void recalcAccount(AssetAccount a) {
        a.setOpeningFeePaid(receipts.sumOpeningFees(a.getId()));
        a.setActivatedDate(a.isActive() ? dateOpeningFeeCompleted(a) : null);
        accounts.save(a);
    }

    public void recalcProject(Project p) {
        p.setTotalDeposited(receipts.sumForProject(p.getId(), ReceiptType.ASSET_DEPOSIT));
        p.setTotalRepaid(receipts.sumForProject(p.getId(), ReceiptType.LOAN_REPAYMENT));

        LocalDate reached = dateThresholdReached(p);
        p.setThresholdReachedDate(reached);
        if (p.getStatus() == ProjectStatus.SAVING || p.getStatus() == ProjectStatus.THRESHOLD_MET) {
            p.setStatus(reached != null ? ProjectStatus.THRESHOLD_MET : ProjectStatus.SAVING);
        }
        if (p.isLoanStarted()) {
            p.setLoanClearedDate(p.getLoanBalance().signum() == 0 ? lastDate(p, ReceiptType.LOAN_REPAYMENT) : null);
        }
        projects.save(p);
    }

    private LocalDate dateThresholdReached(Project p) {
        BigDecimal min = p.getMinimumDeposit();
        if (min == null || min.signum() <= 0) return null;
        BigDecimal running = BigDecimal.ZERO;
        for (Receipt r : receipts.findByProjectIdOrderByReceiptDateAscIdAsc(p.getId())) {
            if (r.isReversed() || r.getType() != ReceiptType.ASSET_DEPOSIT) continue;
            running = running.add(r.getAmount());
            if (running.compareTo(min) >= 0) return r.getReceiptDate();
        }
        return null;
    }

    private LocalDate dateOpeningFeeCompleted(AssetAccount a) {
        BigDecimal running = BigDecimal.ZERO;
        for (Receipt r : receipts.findByAccountIdOrderByReceiptDateAscIdAsc(a.getId())) {
            if (r.isReversed() || r.getType() != ReceiptType.ACCOUNT_OPENING) continue;
            running = running.add(r.getAmount());
            if (running.compareTo(AssetAccount.OPENING_FEE) >= 0) return r.getReceiptDate();
        }
        return null;
    }

    private LocalDate lastDate(Project p, ReceiptType type) {
        LocalDate last = null;
        for (Receipt r : receipts.findByProjectIdOrderByReceiptDateAscIdAsc(p.getId())) {
            if (!r.isReversed() && r.getType() == type) last = r.getReceiptDate();
        }
        return last;
    }
}
