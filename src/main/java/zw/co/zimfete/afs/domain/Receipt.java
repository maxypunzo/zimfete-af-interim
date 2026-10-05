package zw.co.zimfete.afs.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Every dollar received. Daily income sheets, monthly I&E and deposit registers are all built from this table. */
@Entity
@Table(indexes = @Index(columnList = "branch_id, receiptNo"))
public class Receipt {
    /** Captured in this system. */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** From a district return. */
    public static final String SOURCE_IMPORT = "IMPORT";
    /**
     * Balance brought forward from the previous AFM's register: counts towards account and project balances
     * but is not cash received in this system, so it is left out of cash reports.
     */
    public static final String SOURCE_OPENING_BALANCE = "OPENING_BALANCE";
    /**
     * A line from the previous AFM's daily inflow sheets: counts in cash reports for those days but is not
     * linked to a client, so it does not touch balances (those come in as opening balances).
     */
    public static final String SOURCE_OLD_CASHBOOK = "OLD_CASHBOOK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String receiptNo;

    @Column(nullable = false)
    private LocalDate receiptDate;

    @ManyToOne(optional = false)
    private Branch branch;

    @ManyToOne
    private Client client;

    @ManyToOne
    private AssetAccount account;

    /** The project a deposit or repayment belongs to. */
    @ManyToOne
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReceiptType type;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** Months covered, for subscriptions. */
    private Integer months;

    private String paymentMethod;
    private String reference;
    private String capturedBy;
    private String description;

    /** See the SOURCE_ constants. Receipt numbers are unique per branch for new receipts (checked on capture). */
    private String source = SOURCE_MANUAL;

    private boolean reversed;

    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean isCash() {
        return !SOURCE_OPENING_BALANCE.equals(source);
    }

    public String getSourceLabel() {
        return switch (source == null ? SOURCE_MANUAL : source) {
            case SOURCE_IMPORT -> "District return";
            case SOURCE_OPENING_BALANCE -> "B/F old register";
            case SOURCE_OLD_CASHBOOK -> "Old cash book";
            default -> "Captured";
        };
    }

    public String getPayerName() {
        return client != null ? client.getFullName() : description;
    }

    public Long getId() { return id; }
    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }
    public LocalDate getReceiptDate() { return receiptDate; }
    public void setReceiptDate(LocalDate receiptDate) { this.receiptDate = receiptDate; }
    public Branch getBranch() { return branch; }
    public void setBranch(Branch branch) { this.branch = branch; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
    public AssetAccount getAccount() { return account; }
    public void setAccount(AssetAccount account) { this.account = account; }
    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }
    public ReceiptType getType() { return type; }
    public void setType(ReceiptType type) { this.type = type; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Integer getMonths() { return months; }
    public void setMonths(Integer months) { this.months = months; }
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
    public boolean isReversed() { return reversed; }
    public void setReversed(boolean reversed) { this.reversed = reversed; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
