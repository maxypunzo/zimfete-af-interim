package zw.co.zimfete.afs.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.*;

/** Excel outputs: the full master register, the monthly I&E, and the blank district return template. */
@Service
@Transactional(readOnly = true)
public class ExcelExportService {
    public static final String[] RETURN_RECEIPT_COLUMNS = {
            "Date", "Receipt No", "Type", "First Name", "Surname", "National ID", "Phone", "Gender", "Village", "Ward",
            "Account No", "Asset Type", "Asset Description", "Quotation Cost", "Target Date", "Amount", "Months (subs)",
            "Payment Method", "Clerk", "Notes"
    };
    public static final String[] RETURN_EXPENSE_COLUMNS = {
            "Date", "Voucher No", "Category", "Description", "Payee", "Amount", "Clerk"
    };

    private final MemberRepository members;
    private final AssetAccountRepository accounts;
    private final ReceiptRepository receipts;
    private final ExpenseRepository expenses;
    private final BranchRepository branches;
    private final ReportService reports;

    public ExcelExportService(MemberRepository members, AssetAccountRepository accounts, ReceiptRepository receipts,
                              ExpenseRepository expenses, BranchRepository branches, ReportService reports) {
        this.members = members;
        this.accounts = accounts;
        this.receipts = receipts;
        this.expenses = expenses;
        this.branches = branches;
        this.reports = reports;
    }

    public byte[] masterRegister(LocalDate today) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);

            Sheet s = wb.createSheet("District summary");
            header(s, st, "District", "Members", "Accounts", "Opened this month", "Deposits to date", "Deposits this month",
                    "Saving", "Min deposit reached", "Started", "Completed", "Due (14 days)", "Loan book", "Arrears");
            int r = 1;
            for (ReportService.DistrictStats d : reports.districtStats(today)) {
                row(s, st, r++, d.branch().getLabel(), d.members(), d.accounts(), d.accountsThisMonth(), d.depositsTotal(),
                        d.depositsThisMonth(), d.saving(), d.thresholdMet(), d.inProgress(), d.completed(), d.due(), d.loanBook(), d.arrears());
            }
            autosize(s, 13);

            s = wb.createSheet("Members");
            header(s, st, "Member No", "Branch", "First Name", "Surname", "National ID", "Gender", "Phone", "Village", "Ward",
                    "District", "Date Joined", "Joining Fee Paid", "Subs Paid Until", "Months Owed");
            r = 1;
            for (Member m : members.findAll()) {
                row(s, st, r++, m.getMemberNo(), m.getBranch().getName(), m.getFirstName(), m.getSurname(), m.getNationalId(),
                        m.getGender(), m.getPhone(), m.getVillage(), m.getWard(), m.getDistrict(), m.getDateJoined(),
                        m.isJoiningFeePaid() ? "Yes" : "No", m.getSubsPaidUntil(), m.subsMonthsOwed(today));
            }
            autosize(s, 14);

            s = wb.createSheet("Accounts & projects");
            header(s, st, "Account No", "Branch", "Member", "National ID", "Ward", "Opened", "Opened By", "Asset Type", "Asset",
                    "Quotation", "Min Deposit", "Deposited", "Shortfall", "Status", "Threshold Reached", "Target Date",
                    "Started", "Completed", "Months", "Loan Principal", "Interest (30%)", "Total Loan", "Instalment",
                    "Repaid", "Balance", "Arrears");
            r = 1;
            for (AssetAccount a : accounts.findAllByOrderByOpenedDateDescIdDesc()) {
                LoanTerms t = a.getLoanTerms();
                row(s, st, r++, a.getAccountNo(), a.getBranch().getName(), a.getMember().getFullName(), a.getMember().getNationalId(),
                        a.getMember().getWard(), a.getOpenedDate(), a.getOpenedBy(),
                        a.getAssetType() == null ? null : a.getAssetType().getLabel(), a.getAssetDescription(),
                        a.getQuotationCost(), a.getMinimumDeposit(), a.getTotalDeposited(), a.getDepositShortfall(),
                        a.getStatus().getLabel(), a.getThresholdReachedDate(), a.getTargetDate(), a.getProjectStartDate(),
                        a.getCompletionDate(), a.getRepaymentMonths(),
                        t == null ? null : t.principal(), t == null ? null : t.interest(), t == null ? null : t.totalRepayable(),
                        t == null ? null : t.monthlyInstalment(), a.getTotalRepaid(), a.getLoanBalance(), a.getArrears(today));
            }
            autosize(s, 26);

            List<Receipt> all = receipts.find(LocalDate.of(2000, 1, 1), today.plusYears(1), null, null);
            s = wb.createSheet("Deposits");
            header(s, st, "Date", "Receipt No", "Branch", "Member", "National ID", "Ward", "Account No", "Asset", "Quotation", "Amount", "Captured By");
            r = 1;
            for (Receipt x : all) {
                if (x.isReversed() || x.getType() != ReceiptType.ASSET_DEPOSIT) continue;
                AssetAccount a = x.getAccount();
                row(s, st, r++, x.getReceiptDate(), x.getReceiptNo(), x.getBranch().getName(), x.getPayerName(),
                        x.getMember().getNationalId(), x.getMember().getWard(), a.getAccountNo(),
                        a.getAssetType() == null ? null : a.getAssetType().getLabel(), a.getQuotationCost(), x.getAmount(), x.getCapturedBy());
            }
            autosize(s, 11);

            s = wb.createSheet("All receipts");
            header(s, st, "Date", "Receipt No", "Branch", "Type", "Income?", "Member", "Account No", "Amount", "Months",
                    "Payment Method", "Reference", "Captured By", "Source", "Reversed");
            r = 1;
            for (Receipt x : all) {
                row(s, st, r++, x.getReceiptDate(), x.getReceiptNo(), x.getBranch().getName(), x.getType().getLabel(),
                        x.getType().isIncome() ? "Income" : "Client funds", x.getPayerName(),
                        x.getAccount() == null ? null : x.getAccount().getAccountNo(), x.getAmount(), x.getMonths(),
                        x.getPaymentMethod(), x.getReference(), x.getCapturedBy(), x.getSource(), x.isReversed() ? "Yes" : "");
            }
            autosize(s, 14);

            s = wb.createSheet("Expenditure");
            header(s, st, "Date", "Voucher No", "Branch", "Category", "Description", "Payee", "Amount", "Captured By");
            r = 1;
            for (Expense e : expenses.find(LocalDate.of(2000, 1, 1), today.plusYears(1), null)) {
                row(s, st, r++, e.getExpenseDate(), e.getVoucherNo(), e.getBranch().getName(), e.getCategory(),
                        e.getDescription(), e.getPayee(), e.getAmount(), e.getCapturedBy());
            }
            autosize(s, 8);
            return bytes(wb);
        }
    }

    public byte[] monthly(YearMonth month, Long branchId) throws IOException {
        ReportService.MonthlyReport m = reports.monthly(month, branchId);
        ReportService.CashReport t = m.totals();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Sheet s = wb.createSheet("Income & Expenditure");
            int r = 0;
            row(s, st, r++, "ZimFete SACCO — Asset Finance Income & Expenditure");
            row(s, st, r++, t.getTitle());
            r++;
            row(s, st, r++, "INCOME");
            for (ReceiptType rt : ReceiptType.values()) if (rt.isIncome()) row(s, st, r++, rt.getLabel(), t.byType().get(rt));
            row(s, st, r++, "Total income", t.incomeTotal());
            r++;
            row(s, st, r++, "EXPENDITURE");
            for (var e : t.expensesByCategory().entrySet()) row(s, st, r++, e.getKey(), e.getValue());
            row(s, st, r++, "Total expenditure", t.expensesTotal());
            r++;
            row(s, st, r++, "SURPLUS / (DEFICIT)", m.getSurplus());
            r++;
            row(s, st, r++, "CLIENT FUNDS RECEIVED (not income)");
            row(s, st, r++, "Asset finance deposits", t.byType().get(ReceiptType.ASSET_DEPOSIT));
            row(s, st, r++, "Loan repayments", t.byType().get(ReceiptType.LOAN_REPAYMENT));
            r++;
            row(s, st, r++, "New members", t.newMembers());
            row(s, st, r, "Accounts opened", t.accountsOpened());
            autosize(s, 2);

            s = wb.createSheet("Daily income");
            ReceiptType[] types = ReceiptType.values();
            Object[] head = new Object[types.length + 4];
            head[0] = "Date";
            for (int i = 0; i < types.length; i++) head[i + 1] = types[i].getLabel();
            head[types.length + 1] = "Total receipts";
            head[types.length + 2] = "Expenditure";
            head[types.length + 3] = "Net";
            header(s, st, java.util.Arrays.stream(head).map(Object::toString).toArray(String[]::new));
            r = 1;
            for (ReportService.DayRow d : m.days()) {
                Object[] v = new Object[types.length + 4];
                v[0] = d.date();
                for (int i = 0; i < types.length; i++) v[i + 1] = d.byType().get(types[i]);
                v[types.length + 1] = d.receipts();
                v[types.length + 2] = d.expenses();
                v[types.length + 3] = d.net();
                row(s, st, r++, v);
            }
            autosize(s, head.length);

            if (!m.branchRows().isEmpty()) {
                s = wb.createSheet("By district");
                header(s, st, "District", "New members", "Accounts opened", "Income", "Deposits", "Repayments", "Expenditure", "Net cash");
                r = 1;
                for (ReportService.BranchRow b : m.branchRows()) {
                    row(s, st, r++, b.branch().getLabel(), b.newMembers(), b.accountsOpened(), b.income(), b.deposits(),
                            b.repayments(), b.expenses(), b.net());
                }
                autosize(s, 8);
            }
            return bytes(wb);
        }
    }

    /**
     * Blank return for a district clerk to fill offline (Excel or WPS on a phone) and send on WhatsApp.
     * The "Accounts" sheet lists the branch's existing accounts so clerks can quote the right account number.
     */
    public byte[] districtReturnTemplate(Long branchId) throws IOException {
        Branch b = branches.findById(branchId).orElseThrow();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Sheet rec = wb.createSheet("Receipts");
            header(rec, st, RETURN_RECEIPT_COLUMNS);
            Sheet exp = wb.createSheet("Expenditure");
            header(exp, st, RETURN_EXPENSE_COLUMNS);

            Sheet lists = wb.createSheet("Lists");
            row(lists, st, 0, "Receipt types", "Asset types", "Expense categories", "Payment methods");
            String[] methods = {"Cash", "EcoCash", "Bank transfer", "Swipe", "InnBucks", "OneMoney"};
            int max = Math.max(Math.max(ReceiptType.values().length, AssetType.values().length), Math.max(Expense.CATEGORIES.length, methods.length));
            for (int i = 0; i < max; i++) {
                row(lists, st, i + 1,
                        i < ReceiptType.values().length ? ReceiptType.values()[i].name() : null,
                        i < AssetType.values().length ? AssetType.values()[i].name() : null,
                        i < Expense.CATEGORIES.length ? Expense.CATEGORIES[i] : null,
                        i < methods.length ? methods[i] : null);
            }
            autosize(lists, 4);
            dropdown(rec, "Lists!$A$2:$A$" + (ReceiptType.values().length + 1), 2);
            dropdown(rec, "Lists!$B$2:$B$" + (AssetType.values().length + 1), 11);
            dropdown(rec, "Lists!$D$2:$D$" + (methods.length + 1), 17);
            dropdown(exp, "Lists!$C$2:$C$" + (Expense.CATEGORIES.length + 1), 2);

            Sheet acc = wb.createSheet("Accounts");
            header(acc, st, "Account No", "Member", "National ID", "Asset", "Quotation", "Deposited", "Status", "Loan Balance");
            int r = 1;
            for (AssetAccount a : accounts.findAllByOrderByOpenedDateDescIdDesc()) {
                if (!a.getBranch().getId().equals(branchId)) continue;
                row(acc, st, r++, a.getAccountNo(), a.getMember().getFullName(), a.getMember().getNationalId(),
                        a.getAssetType() == null ? null : a.getAssetType().getLabel(), a.getQuotationCost(),
                        a.getTotalDeposited(), a.getStatus().getLabel(), a.getLoanBalance());
            }
            autosize(acc, 8);

            Sheet help = wb.createSheet("How to fill");
            String[] lines = {
                    "ZimFete asset finance return — " + b.getLabel(),
                    "One row per receipt on the Receipts sheet, one row per payment on the Expenditure sheet.",
                    "Date: dd/mm/yyyy. Receipt No: the number on your receipt book (required, used to stop double capture).",
                    "Type: JOINING_FEE ($10), SUBSCRIPTION ($1/month), ACCOUNT_OPENING ($50), ASSET_DEPOSIT, LOAN_REPAYMENT, OTHER_INCOME.",
                    "New member: fill First Name, Surname, National ID (+ Phone, Gender, Village, Ward) on their first row. Later rows only need the National ID.",
                    "ACCOUNT_OPENING: put the account number you issued (or leave blank and HQ will issue one), Asset Type, Asset Description, Quotation Cost, Target Date.",
                    "ASSET_DEPOSIT / LOAN_REPAYMENT: Account No is required (see the Accounts sheet).",
                    "SUBSCRIPTION: Months is optional, worked out from Amount ($1 per month) if left blank.",
                    "Send the file on the WhatsApp group daily, weekly or monthly. Sending the same rows twice is safe."
            };
            for (int i = 0; i < lines.length; i++) row(help, st, i, lines[i]);
            help.setColumnWidth(0, 150 * 256);
            wb.setSheetOrder("How to fill", 0);
            wb.setActiveSheet(1);
            return bytes(wb);
        }
    }

    // ---------------------------------------------------------------- POI helpers

    private static final class Styles {
        final CellStyle head;
        final CellStyle date;
        final CellStyle money;

        Styles(Workbook wb) {
            head = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            head.setFont(f);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CreationHelper h = wb.getCreationHelper();
            date = wb.createCellStyle();
            date.setDataFormat(h.createDataFormat().getFormat("dd/mm/yyyy"));
            money = wb.createCellStyle();
            money.setDataFormat(h.createDataFormat().getFormat("#,##0.00"));
        }
    }

    private static void header(Sheet s, Styles st, String... titles) {
        Row r = s.createRow(0);
        for (int i = 0; i < titles.length; i++) {
            Cell c = r.createCell(i);
            c.setCellValue(titles[i]);
            c.setCellStyle(st.head);
        }
        s.createFreezePane(0, 1);
    }

    private static void row(Sheet s, Styles st, int idx, Object... values) {
        Row r = s.createRow(idx);
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            if (v == null) continue;
            Cell c = r.createCell(i);
            if (v instanceof BigDecimal b) {
                c.setCellValue(b.doubleValue());
                c.setCellStyle(st.money);
            } else if (v instanceof Number n) {
                c.setCellValue(n.doubleValue());
            } else if (v instanceof LocalDate d) {
                c.setCellValue(d);
                c.setCellStyle(st.date);
            } else {
                c.setCellValue(v.toString());
            }
        }
    }

    private static void dropdown(Sheet s, String formula, int column) {
        DataValidationHelper h = s.getDataValidationHelper();
        DataValidation dv = h.createValidation(h.createFormulaListConstraint(formula), new CellRangeAddressList(1, 2000, column, column));
        dv.setShowErrorBox(false);
        s.addValidationData(dv);
    }

    private static void autosize(Sheet s, int cols) {
        for (int i = 0; i < cols; i++) {
            s.autoSizeColumn(i);
            s.setColumnWidth(i, Math.min(Math.max(s.getColumnWidth(i) + 512, 10 * 256), 45 * 256));
        }
    }

    private static byte[] bytes(Workbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }
}
