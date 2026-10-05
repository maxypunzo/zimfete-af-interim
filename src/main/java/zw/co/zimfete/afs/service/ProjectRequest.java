package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import zw.co.zimfete.afs.domain.AssetType;

/** A project (asset) on an account, with an optional first deposit. */
public class ProjectRequest {
    private AssetType assetType;
    private String assetDescription;
    private String supplier;
    private BigDecimal quotationCost;
    private BigDecimal minDepositPercent = BigDecimal.valueOf(50);
    private BigDecimal interestPercent;
    private Integer repaymentMonths;
    private LocalDate targetDate;
    private String notes;
    private LocalDate createdDate;
    private BigDecimal initialDeposit;
    private String depositReceiptNo;
    private String paymentMethod = "Cash";
    private String capturedBy;

    /** True when nothing about the asset has been filled in (so no project should be created). */
    public boolean isEmpty() {
        return assetType == null && (assetDescription == null || assetDescription.isBlank()) && quotationCost == null
                && (initialDeposit == null || initialDeposit.signum() == 0);
    }

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
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public LocalDate getCreatedDate() { return createdDate; }
    public void setCreatedDate(LocalDate createdDate) { this.createdDate = createdDate; }
    public BigDecimal getInitialDeposit() { return initialDeposit; }
    public void setInitialDeposit(BigDecimal initialDeposit) { this.initialDeposit = initialDeposit; }
    public String getDepositReceiptNo() { return depositReceiptNo; }
    public void setDepositReceiptNo(String depositReceiptNo) { this.depositReceiptNo = depositReceiptNo; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getCapturedBy() { return capturedBy; }
    public void setCapturedBy(String capturedBy) { this.capturedBy = capturedBy; }
}
