package zw.co.zimfete.afs.domain;

import java.math.BigDecimal;

/**
 * What a receipt was for. {@code income} marks ZimFete's own revenue; deposits and loan repayments are
 * client money / collections and are reported separately on the cash book.
 */
public enum ReceiptType {
    JOINING_FEE("Joining fee", true, new BigDecimal("10.00")),
    SUBSCRIPTION("Monthly subscription", true, new BigDecimal("1.00")),
    ACCOUNT_OPENING("Account opening fee", true, new BigDecimal("50.00")),
    ASSET_DEPOSIT("Asset finance deposit", false, null),
    LOAN_REPAYMENT("Loan repayment", false, null),
    OTHER_INCOME("Other income", true, null);

    private final String label;
    private final boolean income;
    private final BigDecimal standardAmount;

    ReceiptType(String label, boolean income, BigDecimal standardAmount) {
        this.label = label;
        this.income = income;
        this.standardAmount = standardAmount;
    }

    public String getLabel() { return label; }
    public boolean isIncome() { return income; }
    public BigDecimal getStandardAmount() { return standardAmount; }
    public boolean needsAccount() { return this == ASSET_DEPOSIT || this == LOAN_REPAYMENT; }

    public static ReceiptType parse(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        for (ReceiptType r : values()) {
            if (r.name().equals(t) || r.label.equalsIgnoreCase(text.trim())) return r;
        }
        if (t.contains("JOIN")) return JOINING_FEE;
        if (t.contains("SUB")) return SUBSCRIPTION;
        if (t.contains("OPEN")) return ACCOUNT_OPENING;
        if (t.contains("DEPOSIT")) return ASSET_DEPOSIT;
        if (t.contains("REPAY") || t.contains("LOAN")) return LOAN_REPAYMENT;
        return null;
    }
}
