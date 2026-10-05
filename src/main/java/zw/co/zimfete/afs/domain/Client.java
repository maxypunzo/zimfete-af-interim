package zw.co.zimfete.afs.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * The clients database: everyone ZimFete deals with. A client from the veteran community may also be a SACCO
 * member (joining fee $10, subscription $1 a month). Asset finance needs an account whether or not the client
 * is a member.
 */
@Entity
public class Client {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String clientNo;

    @Column(nullable = false)
    private String firstName;

    private String surname;

    /** Optional: the old registers never captured it. Unique when present. */
    @Column(unique = true)
    private String nationalId;

    private String gender;
    private String phone;
    private String village;
    private String ward;
    private String district;
    private String nextOfKin;

    @ManyToOne(optional = false)
    private Branch branch;

    @Column(nullable = false)
    private LocalDate dateRegistered;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberCategory category = MemberCategory.NOT_VETERAN;

    /** War veteran / collaborator registration number, if known. */
    private String veteranRef;

    /** For widows and descendants: the veteran they are related to. */
    private String relatedVeteran;

    /** SACCO member (veteran community only). */
    private boolean saccoMember;

    private LocalDate memberSince;
    private boolean joiningFeePaid;

    /** Last month (first day of the month) covered by subscriptions; null if none paid. */
    private LocalDate subsPaidUntil;

    @Column(length = 1000)
    private String notes;

    public String getFullName() {
        return (firstName + (surname == null ? "" : " " + surname)).trim();
    }

    /** Whole months of subscription owed up to and including the given month; 0 for non-members. */
    public int subsMonthsOwed(LocalDate asOf) {
        if (!saccoMember || memberSince == null) return 0;
        YearMonth paid = subsPaidUntil != null ? YearMonth.from(subsPaidUntil) : YearMonth.from(memberSince).minusMonths(1);
        return (int) Math.max(0, paid.until(YearMonth.from(asOf), ChronoUnit.MONTHS));
    }

    public Long getId() { return id; }
    public String getClientNo() { return clientNo; }
    public void setClientNo(String clientNo) { this.clientNo = clientNo; }
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
    public Branch getBranch() { return branch; }
    public void setBranch(Branch branch) { this.branch = branch; }
    public LocalDate getDateRegistered() { return dateRegistered; }
    public void setDateRegistered(LocalDate dateRegistered) { this.dateRegistered = dateRegistered; }
    public MemberCategory getCategory() { return category; }
    public void setCategory(MemberCategory category) { this.category = category; }
    public String getVeteranRef() { return veteranRef; }
    public void setVeteranRef(String veteranRef) { this.veteranRef = veteranRef; }
    public String getRelatedVeteran() { return relatedVeteran; }
    public void setRelatedVeteran(String relatedVeteran) { this.relatedVeteran = relatedVeteran; }
    public boolean isSaccoMember() { return saccoMember; }
    public void setSaccoMember(boolean saccoMember) { this.saccoMember = saccoMember; }
    public LocalDate getMemberSince() { return memberSince; }
    public void setMemberSince(LocalDate memberSince) { this.memberSince = memberSince; }
    public boolean isJoiningFeePaid() { return joiningFeePaid; }
    public void setJoiningFeePaid(boolean joiningFeePaid) { this.joiningFeePaid = joiningFeePaid; }
    public LocalDate getSubsPaidUntil() { return subsPaidUntil; }
    public void setSubsPaidUntil(LocalDate subsPaidUntil) { this.subsPaidUntil = subsPaidUntil; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
