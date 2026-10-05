package zw.co.zimfete.afs.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * One asset being financed on an account. The client deposits towards it, the ZimFete committee approves it,
 * funds are disbursed to the supplier and ZimFete's share becomes a loan: (quotation - deposited) plus a
 * once-off interest charge, repaid over the agreed months.
 */
@Entity
public class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private AssetAccount account;

    @Enumerated(EnumType.STRING)
    private AssetType assetType;

    private String assetDescription;
    private String supplier;

    @Column(precision = 14, scale = 2)
    private BigDecimal quotationCost;

    /** Minimum deposit as a percentage of the quotation (policy: 50%). */
    @Column(precision = 5, scale = 2)
    private BigDecimal minDepositPercent;

    /** Once-off interest on the loan, as a percentage (policy: 30%). */
    @Column(precision = 5, scale = 2)
    private BigDecimal interestPercent;

    private Integer repaymentMonths;

    /** Date the client is due to have the project started. Drives the "due" list. */
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectStatus status = ProjectStatus.SAVING;

    private LocalDate createdDate;
    private LocalDate thresholdReachedDate;
    private LocalDate approvedDate;
    @Column(length = 500)
    private String approvalNote;

    /** Funds disbursed / project started. */
    private LocalDate projectStartDate;
    @Column(precision = 14, scale = 2)
    private BigDecimal disbursedAmount;
    private String disbursedTo;

    private LocalDate completionDate;
    private LocalDate loanClearedDate;

    /** Frozen at start: quotation cost less amount deposited. */
    @Column(precision = 14, scale = 2)
    private BigDecimal loanPrincipal;

    @Column(precision = 14, scale = 2)
    private BigDecimal loanInterest;

    /** Monthly instalment as agreed/recorded; when blank it is the total spread evenly over the months. */
    @Column(precision = 14, scale = 2)
    private BigDecimal instalmentAmount;

    /** Why the rate differs from policy: a committee decision. */
    @Column(length = 500)
    private String rateDecision;

    @Column(precision = 14, scale = 2, nullable = false)
    private BigDecimal totalDeposited = BigDecimal.ZERO;

    @Column(precision = 14, scale = 2, nullable = false)
    private BigDecimal totalRepaid = BigDecimal.ZERO;

    @Column(length = 1000)
    private String notes;

    // ---- derived figures -------------------------------------------------------------------

    public Client getClient() {
        return account.getClient();
    }

    public Branch getBranch() {
        return account.getBranch();
    }

    public String getAssetLabel() {
        String type = assetType == null ? "Asset" : assetType.getLabel();
        return assetDescription == null || assetDescription.isBlank() ? type : type + " (" + assetDescription + ")";
    }

    /** "MRE2601ME · Borehole drilling": how a project is named in lists and dropdowns. */
    public String getLabel() {
        return account.getNumberLabel() + " · " + getAssetLabel();
    }

    public BigDecimal getMinimumDeposit() {
        if (quotationCost == null) return null;
        return quotationCost.multiply(minPct()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public BigDecimal getDepositShortfall() {
        BigDecimal min = getMinimumDeposit();
        return min == null ? null : min.subtract(totalDeposited).max(BigDecimal.ZERO);
    }

    public boolean isThresholdReached() {
        BigDecimal min = getMinimumDeposit();
        return min != null && min.signum() > 0 && totalDeposited.compareTo(min) >= 0;
    }

    public int getDepositProgress() {
        BigDecimal min = getMinimumDeposit();
        if (min == null || min.signum() == 0) return 0;
        return totalDeposited.multiply(BigDecimal.valueOf(100)).divide(min, 0, RoundingMode.DOWN).intValue();
    }

    /** Loan figures: frozen ones once started, otherwise a projection from today's deposits. */
    public LoanTerms getLoanTerms() {
        if (loanPrincipal != null && loanInterest != null) {
            return LoanTerms.recorded(loanPrincipal, loanInterest, repaymentMonths, instalmentAmount);
        }
        if (loanPrincipal != null) return LoanTerms.fromPrincipal(loanPrincipal, repaymentMonths, getInterestRate());
        if (quotationCost == null) return null;
        return LoanTerms.calculate(quotationCost, totalDeposited, repaymentMonths, getInterestRate());
    }

    public boolean isLoanStarted() {
        return loanPrincipal != null;
    }

    public BigDecimal getLoanBalance() {
        if (!isLoanStarted()) return null;
        return getLoanTerms().totalRepayable().subtract(totalRepaid).max(BigDecimal.ZERO);
    }

    /** Amount that should have been repaid by the given date if instalments are paid monthly from the start. */
    public BigDecimal getExpectedRepaidBy(LocalDate asOf) {
        if (projectStartDate == null || !isLoanStarted()) return BigDecimal.ZERO;
        LoanTerms t = getLoanTerms();
        long months = ChronoUnit.MONTHS.between(projectStartDate.withDayOfMonth(1), asOf.withDayOfMonth(1));
        months = Math.max(0, Math.min(months, repaymentMonths == null ? 0 : repaymentMonths));
        return t.monthlyInstalment().multiply(BigDecimal.valueOf(months)).min(t.totalRepayable());
    }

    public BigDecimal getArrears(LocalDate asOf) {
        if (!isLoanStarted()) return BigDecimal.ZERO;
        return getExpectedRepaidBy(asOf).subtract(totalRepaid).max(BigDecimal.ZERO);
    }

    public Long getDaysToComplete() {
        if (projectStartDate == null || completionDate == null) return null;
        return ChronoUnit.DAYS.between(projectStartDate, completionDate);
    }

    public BigDecimal getInterestRate() {
        return interestPercent != null ? interestPercent : LoanTerms.DEFAULT_INTEREST_PERCENT;
    }

    private BigDecimal minPct() {
        return minDepositPercent != null ? minDepositPercent : BigDecimal.valueOf(50);
    }

    // ---- accessors -------------------------------------------------------------------------

    public Long getId() { return id; }
    public AssetAccount getAccount() { return account; }
    public void setAccount(AssetAccount account) { this.account = account; }
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
    public BigDecimal getInterestPercent() { return interestPercent; }
    public void setInterestPercent(BigDecimal interestPercent) { this.interestPercent = interestPercent; }
    public Integer getRepaymentMonths() { return repaymentMonths; }
    public void setRepaymentMonths(Integer repaymentMonths) { this.repaymentMonths = repaymentMonths; }
    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }
    public ProjectStatus getStatus() { return status; }
    public void setStatus(ProjectStatus status) { this.status = status; }
    public LocalDate getCreatedDate() { return createdDate; }
    public void setCreatedDate(LocalDate createdDate) { this.createdDate = createdDate; }
    public LocalDate getThresholdReachedDate() { return thresholdReachedDate; }
    public void setThresholdReachedDate(LocalDate thresholdReachedDate) { this.thresholdReachedDate = thresholdReachedDate; }
    public LocalDate getApprovedDate() { return approvedDate; }
    public void setApprovedDate(LocalDate approvedDate) { this.approvedDate = approvedDate; }
    public String getApprovalNote() { return approvalNote; }
    public void setApprovalNote(String approvalNote) { this.approvalNote = approvalNote; }
    public LocalDate getProjectStartDate() { return projectStartDate; }
    public void setProjectStartDate(LocalDate projectStartDate) { this.projectStartDate = projectStartDate; }
    public BigDecimal getDisbursedAmount() { return disbursedAmount; }
    public void setDisbursedAmount(BigDecimal disbursedAmount) { this.disbursedAmount = disbursedAmount; }
    public String getDisbursedTo() { return disbursedTo; }
    public void setDisbursedTo(String disbursedTo) { this.disbursedTo = disbursedTo; }
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
    public BigDecimal getInstalmentAmount() { return instalmentAmount; }
    public void setInstalmentAmount(BigDecimal instalmentAmount) { this.instalmentAmount = instalmentAmount; }
    public String getRateDecision() { return rateDecision; }
    public void setRateDecision(String rateDecision) { this.rateDecision = rateDecision; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
