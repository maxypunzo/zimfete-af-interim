package zw.co.zimfete.afs.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Expenditure paid out at a branch (the "expenditure" side of the daily report). */
@Entity
public class Expense {
    public static final String[] CATEGORIES = {
            "Transport", "Stationery & printing", "Airtime & data", "Bank charges", "Refreshments",
            "Allowances", "Rent & utilities", "Repairs & maintenance", "Assessment / site visit", "Other"
    };

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate expenseDate;

    @ManyToOne(optional = false)
    private Branch branch;

    @Column(nullable = false)
    private String category;

    private String description;
    private String payee;
    private String voucherNo;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    private String capturedBy;
    private String source = "MANUAL";

    public Long getId() { return id; }
    public LocalDate getExpenseDate() { return expenseDate; }
    public void setExpenseDate(LocalDate expenseDate) { this.expenseDate = expenseDate; }
    public Branch getBranch() { return branch; }
    public void setBranch(Branch branch) { this.branch = branch; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPayee() { return payee; }
    public void setPayee(String payee) { this.payee = payee; }
    public String getVoucherNo() { return voucherNo; }
    public void setVoucherNo(String voucherNo) { this.voucherNo = voucherNo; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCapturedBy() { return capturedBy; }
    public void setCapturedBy(String capturedBy) { this.capturedBy = capturedBy; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
