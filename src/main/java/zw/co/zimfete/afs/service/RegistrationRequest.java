package zw.co.zimfete.afs.service;

import java.time.LocalDate;
import zw.co.zimfete.afs.domain.MemberCategory;

/**
 * New client form: personal details, veteran category, and the optional parts done on the same visit —
 * joining the SACCO (veteran community), opening an asset finance account, and the first project.
 */
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
    private LocalDate dateRegistered = LocalDate.now();
    private MemberCategory category = MemberCategory.NOT_VETERAN;
    private String notes;
    private String capturedBy;
    private String paymentMethod = "Cash";

    private boolean joinSacco;
    private MembershipRequest membership = new MembershipRequest();

    private boolean openAccount;
    private AccountRequest account = new AccountRequest();
    private ProjectRequest project = new ProjectRequest();

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
    public LocalDate getDateRegistered() { return dateRegistered; }
    public void setDateRegistered(LocalDate dateRegistered) { this.dateRegistered = dateRegistered; }
    public MemberCategory getCategory() { return category; }
    public void setCategory(MemberCategory category) { this.category = category; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getCapturedBy() { return capturedBy; }
    public void setCapturedBy(String capturedBy) { this.capturedBy = capturedBy; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public boolean isJoinSacco() { return joinSacco; }
    public void setJoinSacco(boolean joinSacco) { this.joinSacco = joinSacco; }
    public MembershipRequest getMembership() { return membership; }
    public void setMembership(MembershipRequest membership) { this.membership = membership; }
    public boolean isOpenAccount() { return openAccount; }
    public void setOpenAccount(boolean openAccount) { this.openAccount = openAccount; }
    public AccountRequest getAccount() { return account; }
    public void setAccount(AccountRequest account) { this.account = account; }
    public ProjectRequest getProject() { return project; }
    public void setProject(ProjectRequest project) { this.project = project; }
}
