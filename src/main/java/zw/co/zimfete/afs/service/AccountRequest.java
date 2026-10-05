package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import zw.co.zimfete.afs.domain.AssetAccount;

/** Opening an asset finance account. The opening fee can be part-paid; the rest is receipted later. */
public class AccountRequest {
    private String accountNo;
    private LocalDate openedDate = LocalDate.now();
    private String openedBy;
    private BigDecimal openingFeeAmount = AssetAccount.OPENING_FEE;
    private String openingReceiptNo;
    private String paymentMethod = "Cash";
    private String notes;

    public String getAccountNo() { return accountNo; }
    public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
    public LocalDate getOpenedDate() { return openedDate; }
    public void setOpenedDate(LocalDate openedDate) { this.openedDate = openedDate; }
    public String getOpenedBy() { return openedBy; }
    public void setOpenedBy(String openedBy) { this.openedBy = openedBy; }
    public BigDecimal getOpeningFeeAmount() { return openingFeeAmount; }
    public void setOpeningFeeAmount(BigDecimal openingFeeAmount) { this.openingFeeAmount = openingFeeAmount; }
    public String getOpeningReceiptNo() { return openingReceiptNo; }
    public void setOpeningReceiptNo(String openingReceiptNo) { this.openingReceiptNo = openingReceiptNo; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
