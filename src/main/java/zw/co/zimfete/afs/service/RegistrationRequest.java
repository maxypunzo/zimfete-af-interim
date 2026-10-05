package zw.co.zimfete.afs.service;

import java.time.LocalDate;

/** New member form: personal details plus what they paid on the day. */
public class RegistrationRequest {
    private Long branchId;
    private String firstName;
    private String surname;
    private String nationalId;
    private String gender;
    private String phone;
    private String village;
    private String ward;
    private String district;
    private String nextOfKin;
    private LocalDate dateJoined = LocalDate.now();
    private String notes;
    private String capturedBy;
    private String paymentMethod = "Cash";

    private boolean payJoiningFee = true;
    private String joiningReceiptNo;
    private Integer subsMonths = 1;
    private String subsReceiptNo;

    private boolean openAccount;
    private AccountRequest account = new AccountRequest();

    public Long getBranchId() { return branchId; }
    public void setBranchId(Long branchId) { this.branchId = branchId; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getSurname() { return surname; }
    public void setSurname(String surname) { this.surname = surname; }
    public String getNationalId() { return nationalId; }
    public void setNationalId(String nationalId) { this.nationalId = nationalId; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getVillage() { return village; }
    public void setVillage(String village) { this.village = village; }
    public String getWard() { return ward; }
    public void setWard(String ward) { this.ward = ward; }
    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }
    public String getNextOfKin() { return nextOfKin; }
    public void setNextOfKin(String nextOfKin) { this.nextOfKin = nextOfKin; }
    public LocalDate getDateJoined() { return dateJoined; }
    public void setDateJoined(LocalDate dateJoined) { this.dateJoined = dateJoined; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getCapturedBy() { return capturedBy; }
    public void setCapturedBy(String capturedBy) { this.capturedBy = capturedBy; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public boolean isPayJoiningFee() { return payJoiningFee; }
    public void setPayJoiningFee(boolean payJoiningFee) { this.payJoiningFee = payJoiningFee; }
    public String getJoiningReceiptNo() { return joiningReceiptNo; }
    public void setJoiningReceiptNo(String joiningReceiptNo) { this.joiningReceiptNo = joiningReceiptNo; }
    public Integer getSubsMonths() { return subsMonths; }
    public void setSubsMonths(Integer subsMonths) { this.subsMonths = subsMonths; }
    public String getSubsReceiptNo() { return subsReceiptNo; }
    public void setSubsReceiptNo(String subsReceiptNo) { this.subsReceiptNo = subsReceiptNo; }
    public boolean isOpenAccount() { return openAccount; }
    public void setOpenAccount(boolean openAccount) { this.openAccount = openAccount; }
    public AccountRequest getAccount() { return account; }
    public void setAccount(AccountRequest account) { this.account = account; }
}
