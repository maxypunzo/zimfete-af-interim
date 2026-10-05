package zw.co.zimfete.afs.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * An asset finance account, opened with the $50 account opening fee. It carries the asset being financed
 * (the "project"): the member deposits towards it until the minimum deposit is reached, ZimFete finances
 * the rest as a loan charged a once-off 30% and repaid over the agreed months.
 */
@Entity
public class AssetAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String accountNo;

    @ManyToOne(optional = false)
    private Member member;

    @ManyToOne(optional = false)
    private Branch branch;

    @Column(nullable = false)
    private LocalDate openedDate;

    /** Clerk who opened the account. */
    private String openedBy;

    @Enumerated(EnumType.STRING)
    private AssetType assetType;

    private String assetDescription;
    private String supplier;

    /** Quotation cost of the asset. */
    @Column(precision = 14, scale = 2)
    private BigDecimal quotationCost;

    /** Minimum deposit as a percentage of the quotation (company policy: 50%). */
    @Column(precision = 5, scale = 2)
    private BigDecimal minDepositPercent;

    /** Agreed repayment period in months. */
    private Integer repaymentMonths;

    /** Date the client expects / is due to have the project started. Drives the "due" list. */
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectStatus status = ProjectStatus.SAVING;

    private LocalDate thresholdReachedDate;
    private LocalDate projectStartDate;
    private LocalDate completionDate;
    private LocalDate loanClearedDate;

    /** Frozen when the project starts: quotation cost less amount deposited. */
    @Column(precision = 14, scale = 2)
    private BigDecimal loanPrincipal;

    @Column(precision = 14, scale = 2)
    private BigDecimal loanInterest;

    /** Running totals, kept in step with receipts by {@code AccountService}. */
    @Column(precision = 14, scale = 2, nullable = false)
    private BigDecimal totalDeposited = BigDecimal.ZERO;

    @Column(precision = 14, scale = 2, nullable = false)
    private BigDecimal totalRepaid = BigDecimal.ZERO;

    @Column(length = 1000)
    private String notes;

    // ---- derived figures -------------------------------------------------------------------

    public BigDecimal getMinimumDeposit() {
        if (quotationCost == null) return null;
        return quotationCost.multiply(pct()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public BigDecimal getDepositShortfall() {
        BigDecimal min = getMinimumDeposit();
        if (min == null) return null;
        return min.subtract(totalDeposited).max(BigDecimal.ZERO);
    }

    public boolean isThresholdReached() {
        BigDecimal min = getMinimumDeposit();
        return min != null && min.signum() > 0 && totalDeposited.compareTo(min) >= 0;
    }

    /** Deposit progress towards the minimum deposit, 0-100+. */
    public int getDepositProgress() {
        BigDecimal min = getMinimumDeposit();
        if (min == null || min.signum() == 0) return 0;
        return totalDeposited.multiply(BigDecimal.valueOf(100)).divide(min, 0, RoundingMode.DOWN).intValue();
    }

    /** Loan figures: frozen ones once started, otherwise a projection from today's deposits. */
    public LoanTerms getLoanTerms() {
        if (loanPrincipal != null) {
            return LoanTerms.fromPrincipal(loanPrincipal, repaymentMonths);
        }
        if (quotationCost == null) return null;
        return LoanTerms.calculate(quotationCost, totalDeposited, repaymentMonths);
    }

    public BigDecimal getLoanBalance() {
        if (loanPrincipal == null) return null;
        return getLoanTerms().totalRepayable().subtract(totalRepaid).max(BigDecimal.ZERO);
    }

    /** Days from project start to completion, for completed projects. */
    public Long getDaysToComplete() {
        if (projectStartDate == null || completionDate == null) return null;
        return java.time.temporal.ChronoUnit.DAYS.between(projectStartDate, completionDate);
    }

    public boolean isLoanStarted() {
        return loanPrincipal != null;
    }

    /** Amount that should have been repaid by the given date if instalments are paid monthly from the start. */
    public BigDecimal getExpectedRepaidBy(LocalDate asOf) {
        if (projectStartDate == null || loanPrincipal == null) return BigDecimal.ZERO;
        LoanTerms t = getLoanTerms();
        long months = java.time.temporal.ChronoUnit.MONTHS.between(projectStartDate.withDayOfMonth(1), asOf.withDayOfMonth(1));
        months = Math.max(0, Math.min(months, repaymentMonths == null ? 0 : repaymentMonths));
        BigDecimal expected = t.monthlyInstalment().multiply(BigDecimal.valueOf(months));
        return expected.min(t.totalRepayable());
    }

    public BigDecimal getArrears(LocalDate asOf) {
        if (!isLoanStarted()) return BigDecimal.ZERO;
        return getExpectedRepaidBy(asOf).subtract(totalRepaid).max(BigDecimal.ZERO);
    }

    private BigDecimal pct() {
        return minDepositPercent != null ? minDepositPercent : BigDecimal.valueOf(50);
    }

    // ---- accessors -------------------------------------------------------------------------

    public Long getId() { return id; }
    public String getAccountNo() { return accountNo; }
    public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
    public Member getMember() { return member; }
    public void setMember(Member member) { this.member = member; }
    public Branch getBranch() { return branch; }
    public void setBranch(Branch branch) { this.branch = branch; }
    public LocalDate getOpenedDate() { return openedDate; }
    public void setOpenedDate(LocalDate openedDate) { this.openedDate = openedDate; }
    public String getOpenedBy() { return openedBy; }
    public void setOpenedBy(String openedBy) { this.openedBy = openedBy; }
    public AssetType getAssetType() { return assetType; }
    public void setAssetType(AssetType assetType) { this.assetType = assetType; }
    public String getAssetDescription() { return assetDescription; }
    public void setAssetDescription(String assetDescription) { this.assetDescription = assetDescription; }
    public String getSupplier() { return supplier; }
    public void setSupplier(String supplier) { this.supplier = supplier; }
    public BigDecimal getQuotationCost() { return quotationCost; }
    public void setQuotationCost(BigDecimal quotationCost) { this.quotationCost = quotationCost; }
    public BigDecimal getMinDepositPercent() { return minDepositPercent; }
    public void setMinDepositPercent(BigDecimal minDepositPercent) { this.minDepositPercent = minDepositPercent; }
    public Integer getRepaymentMonths() { return repaymentMonths; }
    public void setRepaymentMonths(Integer repaymentMonths) { this.repaymentMonths = repaymentMonths; }
    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }
    public ProjectStatus getStatus() { return status; }
    public void setStatus(ProjectStatus status) { this.status = status; }
    public LocalDate getThresholdReachedDate() { return thresholdReachedDate; }
    public void setThresholdReachedDate(LocalDate thresholdReachedDate) { this.thresholdReachedDate = thresholdReachedDate; }
    public LocalDate getProjectStartDate() { return projectStartDate; }
    public void setProjectStartDate(LocalDate projectStartDate) { this.projectStartDate = projectStartDate; }
    public LocalDate getCompletionDate() { return completionDate; }
    public void setCompletionDate(LocalDate completionDate) { this.completionDate = completionDate; }
    public LocalDate getLoanClearedDate() { return loanClearedDate; }
    public void setLoanClearedDate(LocalDate loanClearedDate) { this.loanClearedDate = loanClearedDate; }
    public BigDecimal getLoanPrincipal() { return loanPrincipal; }
    public void setLoanPrincipal(BigDecimal loanPrincipal) { this.loanPrincipal = loanPrincipal; }
    public BigDecimal getLoanInterest() { return loanInterest; }
    public void setLoanInterest(BigDecimal loanInterest) { this.loanInterest = loanInterest; }
    public BigDecimal getTotalDeposited() { return totalDeposited; }
    public void setTotalDeposited(BigDecimal totalDeposited) { this.totalDeposited = totalDeposited; }
    public BigDecimal getTotalRepaid() { return totalRepaid; }
    public void setTotalRepaid(BigDecimal totalRepaid) { this.totalRepaid = totalRepaid; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
