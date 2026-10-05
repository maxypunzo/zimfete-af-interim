package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.zimfete.afs.config.AfsProperties;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.AssetAccountRepository;
import zw.co.zimfete.afs.repo.ProjectRepository;

/**
 * Projects through their lifecycle: saving → minimum deposit reached → committee approval → funds disbursed
 * (loan fixed) → completed.
 */
@Service
public class ProjectService {
    private final ProjectRepository projects;
    private final AssetAccountRepository accounts;
    private final ReceiptService receiptService;
    private final StatusService status;
    private final AfsProperties props;

    public ProjectService(ProjectRepository projects, AssetAccountRepository accounts, ReceiptService receiptService,
                          StatusService status, AfsProperties props) {
        this.projects = projects;
        this.accounts = accounts;
        this.receiptService = receiptService;
        this.status = status;
        this.props = props;
    }

    @Transactional
    public Project create(Long accountId, ProjectRequest req, String source) {
        AssetAccount a = accounts.findById(accountId).orElseThrow(() -> new BusinessException("Account not found."));
        if (a.isClosed()) throw new BusinessException("Account " + a.getAccountNo() + " is closed.");
        Project p = new Project();
        p.setAccount(a);
        p.setCreatedDate(req.getCreatedDate() != null ? req.getCreatedDate() : LocalDate.now());
        p.setInterestPercent(props.interestPercent());
        copy(req, p);
        projects.saveAndFlush(p);

        if (req.getInitialDeposit() != null && req.getInitialDeposit().signum() > 0) {
            ReceiptRequest dep = new ReceiptRequest();
            dep.setBranchId(a.getBranch().getId());
            dep.setReceiptDate(p.getCreatedDate());
            dep.setType(ReceiptType.ASSET_DEPOSIT);
            dep.setProjectId(p.getId());
            dep.setAmount(req.getInitialDeposit());
            dep.setReceiptNo(req.getDepositReceiptNo());
            dep.setPaymentMethod(req.getPaymentMethod());
            dep.setCapturedBy(req.getCapturedBy());
            dep.setSource(source);
            receiptService.record(dep);
        }
        status.recalcProject(p);
        return p;
    }

    @Transactional
    public Project update(Long id, ProjectRequest req) {
        Project p = get(id);
        if (p.isLoanStarted() && req.getQuotationCost() != null && p.getQuotationCost() != null
                && req.getQuotationCost().compareTo(p.getQuotationCost()) != 0) {
            throw new BusinessException("The loan has already been fixed; the quotation can no longer change.");
        }
        copy(req, p);
        status.recalcProject(p);
        return p;
    }

    /**
     * Records the ZimFete committee's approval. Normally only once the minimum deposit is reached; approving
     * below it needs the committee's reason on record.
     */
    @Transactional
    public Project approve(Long id, LocalDate date, String note) {
        Project p = get(id);
        if (p.getStatus() != ProjectStatus.SAVING && p.getStatus() != ProjectStatus.THRESHOLD_MET) {
            throw new BusinessException("Only projects still saving or awaiting the committee can be approved.");
        }
        if (p.getQuotationCost() == null) throw new BusinessException("Capture the quotation cost before approval.");
        if (p.getStatus() == ProjectStatus.SAVING && (note == null || note.isBlank())) {
            throw new BusinessException("The minimum deposit is not reached (short by $" + p.getDepositShortfall()
                    + "). To approve anyway, record the committee's reason.");
        }
        p.setStatus(ProjectStatus.APPROVED);
        p.setApprovedDate(date != null ? date : LocalDate.now());
        p.setApprovalNote(note == null || note.isBlank() ? null : note.trim());
        projects.save(p);
        return p;
    }

    /** Funds disbursed to the supplier: the project starts and the loan is fixed. */
    @Transactional
    public Project start(Long id, LocalDate startDate, Integer months, BigDecimal interestPercent,
                         BigDecimal disbursedAmount, String disbursedTo) {
        Project p = get(id);
        if (p.getStatus() != ProjectStatus.APPROVED) throw new BusinessException("The committee must approve the project before funds are disbursed.");
        if (months == null || months <= 0) throw new BusinessException("Enter the agreed repayment period in months.");
        if (interestPercent != null) {
            if (interestPercent.signum() < 0 || interestPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BusinessException("Interest must be between 0% and 100%.");
            }
            p.setInterestPercent(interestPercent);
        }
        p.setRepaymentMonths(months);
        p.setProjectStartDate(startDate != null ? startDate : LocalDate.now());
        p.setDisbursedAmount(disbursedAmount != null ? disbursedAmount : p.getQuotationCost());
        p.setDisbursedTo(disbursedTo == null || disbursedTo.isBlank() ? p.getSupplier() : disbursedTo.trim());
        LoanTerms t = LoanTerms.calculate(p.getQuotationCost(), p.getTotalDeposited(), months, p.getInterestRate());
        p.setLoanPrincipal(t.principal());
        p.setLoanInterest(t.interest());
        p.setStatus(ProjectStatus.IN_PROGRESS);
        status.recalcProject(p);
        return p;
    }

    /** Asset delivered / installed. Loan repayments continue until the balance is cleared. */
    @Transactional
    public Project complete(Long id, LocalDate completionDate) {
        Project p = get(id);
        if (p.getStatus() != ProjectStatus.IN_PROGRESS) throw new BusinessException("Only started projects can be completed.");
        LocalDate d = completionDate != null ? completionDate : LocalDate.now();
        if (d.isBefore(p.getProjectStartDate())) throw new BusinessException("Completion date is before the start date.");
        p.setCompletionDate(d);
        p.setStatus(ProjectStatus.COMPLETED);
        return projects.save(p);
    }

    @Transactional
    public Project cancel(Long id, String reason) {
        Project p = get(id);
        if (p.isLoanStarted()) throw new BusinessException("A project with funds disbursed cannot be cancelled.");
        p.setStatus(ProjectStatus.CANCELLED);
        p.setNotes(((p.getNotes() == null ? "" : p.getNotes() + "\n") + "Cancelled " + LocalDate.now() + ": " + (reason == null ? "" : reason)).trim());
        return projects.save(p);
    }

    public Project get(Long id) {
        return projects.findById(id).orElseThrow(() -> new BusinessException("Project not found."));
    }

    public List<Project> forAccount(Long accountId) {
        return projects.findByAccountIdOrderByIdAsc(accountId);
    }

    private static void copy(ProjectRequest req, Project p) {
        if (req.getQuotationCost() != null && req.getQuotationCost().signum() < 0) throw new BusinessException("Quotation cost cannot be negative.");
        p.setAssetType(req.getAssetType());
        p.setAssetDescription(req.getAssetDescription() == null || req.getAssetDescription().isBlank() ? null : req.getAssetDescription().trim());
        p.setSupplier(req.getSupplier());
        if (!p.isLoanStarted()) {
            p.setQuotationCost(req.getQuotationCost());
            p.setRepaymentMonths(req.getRepaymentMonths());
            if (req.getInterestPercent() != null) p.setInterestPercent(req.getInterestPercent());
        }
        p.setMinDepositPercent(req.getMinDepositPercent() != null ? req.getMinDepositPercent() : BigDecimal.valueOf(50));
        p.setTargetDate(req.getTargetDate());
        p.setNotes(req.getNotes());
    }
}
