package zw.co.zimfete.afs.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.time.YearMonth;

/** The clients database: one row per SACCO member. */
@Entity
public class Member {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String memberNo;

    @NotBlank
    @Column(nullable = false)
    private String firstName;

    @NotBlank
    @Column(nullable = false)
    private String surname;

    @NotBlank
    @Column(nullable = false, unique = true)
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
    private LocalDate dateJoined;

    private boolean joiningFeePaid;

    /** Last month (first day of the month) covered by the $1 monthly subscription; null if none paid. */
    private LocalDate subsPaidUntil;

    @Column(length = 1000)
    private String notes;

    public String getFullName() {
        return (firstName + " " + surname).trim();
    }

    /** Whole months of subscription owed up to and including the given month. */
    public int subsMonthsOwed(LocalDate asOf) {
        YearMonth due = YearMonth.from(asOf);
        YearMonth paid = subsPaidUntil != null ? YearMonth.from(subsPaidUntil) : YearMonth.from(dateJoined).minusMonths(1);
        long owed = paid.until(due, java.time.temporal.ChronoUnit.MONTHS);
        return (int) Math.max(0, owed);
    }

    public Long getId() { return id; }
    public String getMemberNo() { return memberNo; }
    public void setMemberNo(String memberNo) { this.memberNo = memberNo; }
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
    public LocalDate getDateJoined() { return dateJoined; }
    public void setDateJoined(LocalDate dateJoined) { this.dateJoined = dateJoined; }
    public boolean isJoiningFeePaid() { return joiningFeePaid; }
    public void setJoiningFeePaid(boolean joiningFeePaid) { this.joiningFeePaid = joiningFeePaid; }
    public LocalDate getSubsPaidUntil() { return subsPaidUntil; }
    public void setSubsPaidUntil(LocalDate subsPaidUntil) { this.subsPaidUntil = subsPaidUntil; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
