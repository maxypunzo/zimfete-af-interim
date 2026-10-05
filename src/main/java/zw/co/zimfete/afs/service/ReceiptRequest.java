package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import zw.co.zimfete.afs.domain.ReceiptType;

/** Everything needed to record one receipt; also the backing bean of the receipt form. */
public class ReceiptRequest {
    private Long branchId;
    private LocalDate receiptDate = LocalDate.now();
    private ReceiptType type;
    private Long memberId;
    private Long accountId;
    private BigDecimal amount;
    private Integer months;
    private String receiptNo;
    private String paymentMethod = "Cash";
    private String reference;
    private String capturedBy;
    private String description;
    private String source = "MANUAL";

    public Long getBranchId() { return branchId; }
    public void setBranchId(Long branchId) { this.branchId = branchId; }
    public LocalDate getReceiptDate() { return receiptDate; }
    public void setReceiptDate(LocalDate receiptDate) { this.receiptDate = receiptDate; }
    public ReceiptType getType() { return type; }
    public void setType(ReceiptType type) { this.type = type; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Integer getMonths() { return months; }
    public void setMonths(Integer months) { this.months = months; }
    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getCapturedBy() { return capturedBy; }
    public void setCapturedBy(String capturedBy) { this.capturedBy = capturedBy; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
