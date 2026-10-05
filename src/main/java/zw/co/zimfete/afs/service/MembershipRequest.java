package zw.co.zimfete.afs.service;

import java.time.LocalDate;
import zw.co.zimfete.afs.domain.MemberCategory;

/** Enrolling a client from the veteran community as a SACCO member, and what they paid on the day. */
public class MembershipRequest {
    private MemberCategory category;
    private String veteranRef;
    private String relatedVeteran;
    private LocalDate memberSince = LocalDate.now();
    private boolean payJoiningFee = true;
    private String joiningReceiptNo;
    private Integer subsMonths = 1;
    private String subsReceiptNo;
    private String paymentMethod = "Cash";
    private String capturedBy;

    public MemberCategory getCategory() { return category; }
    public void setCategory(MemberCategory category) { this.category = category; }
    public String getVeteranRef() { return veteranRef; }
    public void setVeteranRef(String veteranRef) { this.veteranRef = veteranRef; }
    public String getRelatedVeteran() { return relatedVeteran; }
    public void setRelatedVeteran(String relatedVeteran) { this.relatedVeteran = relatedVeteran; }
    public LocalDate getMemberSince() { return memberSince; }
    public void setMemberSince(LocalDate memberSince) { this.memberSince = memberSince; }
    public boolean isPayJoiningFee() { return payJoiningFee; }
    public void setPayJoiningFee(boolean payJoiningFee) { this.payJoiningFee = payJoiningFee; }
    public String getJoiningReceiptNo() { return joiningReceiptNo; }
    public void setJoiningReceiptNo(String joiningReceiptNo) { this.joiningReceiptNo = joiningReceiptNo; }
    public Integer getSubsMonths() { return subsMonths; }
    public void setSubsMonths(Integer subsMonths) { this.subsMonths = subsMonths; }
    public String getSubsReceiptNo() { return subsReceiptNo; }
    public void setSubsReceiptNo(String subsReceiptNo) { this.subsReceiptNo = subsReceiptNo; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getCapturedBy() { return capturedBy; }
    public void setCapturedBy(String capturedBy) { this.capturedBy = capturedBy; }
}
