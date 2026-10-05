package zw.co.zimfete.afs.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An asset finance account. The $50 opening fee may be paid in instalments; the account is active once it is
 * paid in full. One account can carry several projects (e.g. a borehole, later fencing).
 */
@Entity
public class AssetAccount {
    public static final BigDecimal OPENING_FEE = new BigDecimal("50.00");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String accountNo;

    @ManyToOne(optional = false)
    private Client client;

    @ManyToOne(optional = false)
    private Branch branch;

    @Column(nullable = false)
    private LocalDate openedDate;

    /** Clerk who opened the account. */
    private String openedBy;

    /** Running total of opening fee receipts, kept in step by {@code StatusService}. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal openingFeePaid = BigDecimal.ZERO;

    private LocalDate activatedDate;
    private boolean closed;

    @Column(length = 1000)
    private String notes;

    public boolean isActive() {
        return !closed && openingFeePaid.compareTo(OPENING_FEE) >= 0;
    }

    public BigDecimal getOpeningFeeBalance() {
        return OPENING_FEE.subtract(openingFeePaid).max(BigDecimal.ZERO);
    }

    public String getStatusLabel() {
        return closed ? "Closed" : isActive() ? "Active" : "Inactive (opening fee $" + getOpeningFeeBalance() + " due)";
    }

    public Long getId() { return id; }
    public String getAccountNo() { return accountNo; }
    public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
    public Branch getBranch() { return branch; }
    public void setBranch(Branch branch) { this.branch = branch; }
    public LocalDate getOpenedDate() { return openedDate; }
    public void setOpenedDate(LocalDate openedDate) { this.openedDate = openedDate; }
    public String getOpenedBy() { return openedBy; }
    public void setOpenedBy(String openedBy) { this.openedBy = openedBy; }
    public BigDecimal getOpeningFeePaid() { return openingFeePaid; }
    public void setOpeningFeePaid(BigDecimal openingFeePaid) { this.openingFeePaid = openingFeePaid; }
    public LocalDate getActivatedDate() { return activatedDate; }
    public void setActivatedDate(LocalDate activatedDate) { this.activatedDate = activatedDate; }
    public boolean isClosed() { return closed; }
    public void setClosed(boolean closed) { this.closed = closed; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
