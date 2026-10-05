package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import zw.co.zimfete.afs.domain.AssetType;

/** Asset / project details captured when opening or editing an asset finance account. */
public class AccountRequest {
    private String accountNo;
    private LocalDate openedDate = LocalDate.now();
    private String openedBy;
    private AssetType assetType;
    private String assetDescription;
    private String supplier;
    private BigDecimal quotationCost;
    private BigDecimal minDepositPercent = BigDecimal.valueOf(50);
    private Integer repaymentMonths;
    private LocalDate targetDate;
    private String notes;
    /** Optional receipt number from the receipt book for the $50 opening fee. */
    private String openingReceiptNo;
    /** Optional first deposit paid on the day the account is opened. */
    private BigDecimal initialDeposit;
    private String depositReceiptNo;
    private String paymentMethod = "Cash";

    public String getAccountNo() { return accountNo; }
    public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
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
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getOpeningReceiptNo() { return openingReceiptNo; }
    public void setOpeningReceiptNo(String openingReceiptNo) { this.openingReceiptNo = openingReceiptNo; }
    public BigDecimal getInitialDeposit() { return initialDeposit; }
    public void setInitialDeposit(BigDecimal initialDeposit) { this.initialDeposit = initialDeposit; }
    public String getDepositReceiptNo() { return depositReceiptNo; }
    public void setDepositReceiptNo(String depositReceiptNo) { this.depositReceiptNo = depositReceiptNo; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
}
