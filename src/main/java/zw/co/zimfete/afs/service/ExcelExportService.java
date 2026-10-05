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
            "District / Location", "Category", "Account No", "Asset Type", "Asset Description", "Quotation Cost", "Target Date",
            "Amount", "Months (subs)", "Payment Method", "Clerk", "Notes"
    };
    public static final String[] RETURN_EXPENSE_COLUMNS = {
            "Date", "Voucher No", "Category", "Description", "Payee", "Amount", "Clerk"
    };

    private final ClientRepository clients;
    private final AssetAccountRepository accounts;
    private final ProjectRepository projects;
    private final ReceiptRepository receipts;
    private final ExpenseRepository expenses;
    private final BranchRepository branches;
    private final ReportService reports;

    public ExcelExportService(ClientRepository clients, AssetAccountRepository accounts, ProjectRepository projects,
                              ReceiptRepository receipts, ExpenseRepository expenses, BranchRepository branches, ReportService reports) {
        this.clients = clients;
        this.accounts = accounts;
        this.projects = projects;
        this.receipts = receipts;
        this.expenses = expenses;
        this.branches = branches;
        this.reports = reports;
    }

    public byte[] masterRegister(LocalDate today) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);

            Sheet s = wb.createSheet("District summary");
            header(s, st, "District", "Clients", "SACCO members", "Accounts", "Active accounts", "Opened this month",
                    "Deposits to date", "Deposits this month", "Saving", "Awaiting committee", "Approved", "Started",
                    "Completed", "Due (14 days)", "Loan book", "Arrears");
            int r = 1;
            for (ReportService.DistrictStats d : reports.districtStats(today)) {
                row(s, st, r++, d.branch().getLabel(), d.clients(), d.members(), d.accounts(), d.activeAccounts(),
                        d.accountsThisMonth(), d.depositsTotal(), d.depositsThisMonth(), d.saving(), d.awaitingApproval(),
                        d.approved(), d.inProgress(), d.completed(), d.due(), d.loanBook(), d.arrears());
            }
            autosize(s, 16);

            s = wb.createSheet("Membership");
            MemberCategory[] cats = java.util.Arrays.stream(MemberCategory.values()).filter(MemberCategory::isVeteranCommunity).toArray(MemberCategory[]::new);
            String[] head = new String[cats.length + 2];
            head[0] = "District";
            for (int i = 0; i < cats.length; i++) head[i + 1] = cats[i].getLabel();
            head[cats.length + 1] = "Total members";
            header(s, st, head);
            r = 1;
            for (ReportService.MembershipRow m : reports.membership()) {
                Object[] v = new Object[cats.length + 2];
                v[0] = m.branch().getLabel();
                for (int i = 0; i < cats.length; i++) v[i + 1] = m.byCategory().get(cats[i]);
                v[cats.length + 1] = m.total();
                row(s, st, r++, v);
            }
            autosize(s, head.length);

            s = wb.createSheet("Clients");
            header(s, st, "Client No", "Branch", "First Name", "Surname", "National ID", "Gender", "Phone", "Village", "Ward",
                    "District", "Registered", "Category", "Veteran Ref", "Related Veteran", "SACCO Member", "Member Since",
                    "Joining Fee Paid", "Subs Paid Until", "Months Owed");
            r = 1;
            for (Client c : clients.findAll()) {
                row(s, st, r++, c.getClientNo(), c.getBranch().getName(), c.getFirstName(), c.getSurname(), c.getNationalId(),
                        c.getGender(), c.getPhone(), c.getVillage(), c.getWard(), c.getDistrict(), c.getDateRegistered(),
                        c.getCategory().getLabel(), c.getVeteranRef(), c.getRelatedVeteran(), c.isSaccoMember() ? "Yes" : "No",
                        c.getMemberSince(), c.isSaccoMember() ? (c.isJoiningFeePaid() ? "Yes" : "No") : null,
                        c.getSubsPaidUntil(), c.isSaccoMember() ? c.subsMonthsOwed(today) : null);
            }
            autosize(s, 19);

            s = wb.createSheet("Accounts");
            header(s, st, "Account No", "Branch", "Client", "Phone", "Ward", "Opened", "Opened By", "Opening Fee Paid",
                    "Active", "Activated", "Projects");
            r = 1;
            for (AssetAccount a : accounts.findAllByOrderByOpenedDateDescIdDesc()) {
                row(s, st, r++, a.getAccountNo(), a.getBranch().getName(), a.getClient().getFullName(), a.getClient().getPhone(),
                        a.getClient().getWard(), a.getOpenedDate(), a.getOpenedBy(), a.getOpeningFeePaid(), a.getStatusLabel(),
                        a.getActivatedDate(), projects.findByAccountIdOrderByIdAsc(a.getId()).size());
            }
            autosize(s, 11);

            s = wb.createSheet("Projects");
            header(s, st, "Account No", "Branch", "Client", "Ward", "Asset Type", "Asset", "Supplier", "Quotation", "Min Deposit",
                    "Deposited", "Shortfall", "Status", "Min Reached", "Approved", "Approval Note", "Target Date", "Started",
                    "Disbursed", "Completed", "Days", "Months", "Interest %", "Loan Principal", "Interest", "Total Loan",
                    "Instalment", "Repaid", "Balance", "Arrears");
            r = 1;
            for (Project p : projects.findAllOrdered()) {
                LoanTerms t = p.getLoanTerms();
                row(s, st, r++, p.getAccount().getAccountNo(), p.getBranch().getName(), p.getClient().getFullName(), p.getClient().getWard(),
                        p.getAssetType() == null ? null : p.getAssetType().getLabel(), p.getAssetDescription(), p.getSupplier(),
                        p.getQuotationCost(), p.getMinimumDeposit(), p.getTotalDeposited(), p.getDepositShortfall(),
                        p.getStatus().getLabel(), p.getThresholdReachedDate(), p.getApprovedDate(), p.getApprovalNote(),
                        p.getTargetDate(), p.getProjectStartDate(), p.getDisbursedAmount(), p.getCompletionDate(), p.getDaysToComplete(),
                        p.getRepaymentMonths(), p.getInterestRate(),
                        t == null ? null : t.principal(), t == null ? null : t.interest(), t == null ? null : t.totalRepayable(),
                        t == null ? null : t.monthlyInstalment(), p.getTotalRepaid(), p.getLoanBalance(), p.getArrears(today));
            }
            autosize(s, 29);

            List<Receipt> all = receipts.find(LocalDate.of(2000, 1, 1), today.plusYears(1), null, null);
            s = wb.createSheet("Deposits");
            header(s, st, "Date", "Receipt No", "Branch", "Client", "Ward", "Account No", "Asset", "Quotation", "Amount", "Captured By");
            r = 1;
            for (Receipt x : all) {
                if (x.isReversed() || x.getType() != ReceiptType.ASSET_DEPOSIT) continue;
                Project p = x.getProject();
                row(s, st, r++, x.getReceiptDate(), x.getReceiptNo(), x.getBranch().getName(), x.getPayerName(),
                        x.getClient().getWard(), p.getAccount().getAccountNo(), p.getAssetLabel(), p.getQuotationCost(),
                        x.getAmount(), x.getCapturedBy());
            }
            autosize(s, 10);

            s = wb.createSheet("All receipts");
            header(s, st, "Date", "Receipt No", "Branch", "Type", "Income?", "Client", "Account No", "Project", "Amount", "Months",
                    "Payment Method", "Reference", "Captured By", "Source", "Reversed");
            r = 1;
            for (Receipt x : all) {
                row(s, st, r++, x.getReceiptDate(), x.getReceiptNo(), x.getBranch().getName(), x.getType().getLabel(),
                        x.getType().isIncome() ? "Income" : "Client funds", x.getPayerName(),
                        x.getAccount() == null ? null : x.getAccount().getAccountNo(),
                        x.getProject() == null ? null : x.getProject().getAssetLabel(), x.getAmount(), x.getMonths(),
                        x.getPaymentMethod(), x.getReference(), x.getCapturedBy(), x.getSource(), x.isReversed() ? "Yes" : "");
            }
            autosize(s, 15);

            s = wb.createSheet("Disbursements");
            header(s, st, "Date", "Branch", "Client", "Account No", "Project", "Paid To", "Amount");
            r = 1;
            for (Project p : projects.disbursedBetween(LocalDate.of(2000, 1, 1), today.plusYears(1), null)) {
                row(s, st, r++, p.getProjectStartDate(), p.getBranch().getName(), p.getClient().getFullName(),
                        p.getAccount().getAccountNo(), p.getAssetLabel(), p.getDisbursedTo(), p.getDisbursedAmount());
            }
            autosize(s, 7);

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
            row(s, st, r++, "CLIENT FUNDS (not income)");
            row(s, st, r++, "Asset finance deposits received", t.getDeposits());
            row(s, st, r++, "Loan repayments received", t.getRepayments());
            row(s, st, r++, "Funds disbursed to projects (loans)", t.disbursementsTotal());
            r++;
            row(s, st, r++, "Net cash (all inflow − all outflow)", t.net());
            r++;
            row(s, st, r++, "New SACCO members", t.newMembers());
            row(s, st, r++, "New clients", t.newClients());
            row(s, st, r, "Accounts opened", t.accountsOpened());
            autosize(s, 2);

            s = wb.createSheet("Daily income");
            ReceiptType[] types = ReceiptType.values();
            Object[] head = new Object[types.length + 5];
            head[0] = "Date";
            for (int i = 0; i < types.length; i++) head[i + 1] = types[i].getLabel();
            head[types.length + 1] = "Total inflow";
            head[types.length + 2] = "Expenditure";
            head[types.length + 3] = "Loans disbursed";
            head[types.length + 4] = "Net";
            header(s, st, java.util.Arrays.stream(head).map(Object::toString).toArray(String[]::new));
            r = 1;
            for (ReportService.DayRow d : m.days()) {
                Object[] v = new Object[types.length + 5];
                v[0] = d.date();
                for (int i = 0; i < types.length; i++) v[i + 1] = d.byType().get(types[i]);
                v[types.length + 1] = d.receipts();
                v[types.length + 2] = d.expenses();
                v[types.length + 3] = d.disbursements();
                v[types.length + 4] = d.net();
                row(s, st, r++, v);
            }
            autosize(s, head.length);

            if (!m.branchRows().isEmpty()) {
                s = wb.createSheet("By district");
                header(s, st, "District", "New members", "Accounts opened", "Income", "Deposits", "Repayments", "Expenditure",
                        "Loans disbursed", "Net cash");
                r = 1;
                for (ReportService.BranchRow b : m.branchRows()) {
                    row(s, st, r++, b.branch().getLabel(), b.newMembers(), b.accountsOpened(), b.income(), b.deposits(),
                            b.repayments(), b.expenses(), b.disbursements(), b.net());
                }
                autosize(s, 9);
            }
            return bytes(wb);
        }
    }

    /** Where the branch code is kept in the return, so an upload under the wrong branch is caught. */
    public static final String BRANCH_CELL_SHEET = "Lists";
    public static final int BRANCH_CELL_COL = 6;

    /**
     * The workbook a district clerk keeps (Excel or WPS on a phone, offline) and sends on WhatsApp. Receipts and
     * Expenditure are typed in once; the Summary sheet works out their daily/weekly/monthly report from them; HQ
     * uploads the same file and every row posts. The Accounts sheet lists the branch's accounts and projects.
     */
    public byte[] districtReturnTemplate(Long branchId) throws IOException {
        Branch b = branches.findById(branchId).orElseThrow();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            int rows = 1500;

            Sheet rec = wb.createSheet("Receipts");
            header(rec, st, RETURN_RECEIPT_COLUMNS);
            Sheet exp = wb.createSheet("Expenditure");
            header(exp, st, RETURN_EXPENSE_COLUMNS);
            for (int r = 1; r <= rows; r++) {
                Row rr = rec.createRow(r);
                for (int c : new int[] {0, 16}) rr.createCell(c).setCellStyle(st.date);
                for (int c : new int[] {15, 17}) rr.createCell(c).setCellStyle(st.money);
                Row er = exp.createRow(r);
                er.createCell(0).setCellStyle(st.date);
                er.createCell(5).setCellStyle(st.money);
            }

            Sheet lists = wb.createSheet("Lists");
            row(lists, st, 0, "Receipt types", "Asset types", "Expense categories", "Payment methods", "Categories", "Gender", "Branch");
            String[] methods = {"Cash", "EcoCash", "Bank transfer", "Swipe", "InnBucks", "OneMoney"};
            MemberCategory[] cats = java.util.Arrays.stream(MemberCategory.values()).filter(MemberCategory::isVeteranCommunity).toArray(MemberCategory[]::new);
            String[] genders = {"Female", "Male"};
            int max = Math.max(Math.max(ReceiptType.values().length, AssetType.values().length), Math.max(Expense.CATEGORIES.length, methods.length));
            for (int i = 0; i < max; i++) {
                row(lists, st, i + 1,
                        i < ReceiptType.values().length ? ReceiptType.values()[i].name() : null,
                        i < AssetType.values().length ? AssetType.values()[i].name() : null,
                        i < Expense.CATEGORIES.length ? Expense.CATEGORIES[i] : null,
                        i < methods.length ? methods[i] : null,
                        i < cats.length ? cats[i].name() : null,
                        i < genders.length ? genders[i] : null,
                        i == 0 ? b.getCode() : null);
            }
            autosize(lists, 7);
            dropdown(rec, "Lists!$A$2:$A$" + (ReceiptType.values().length + 1), 2, rows);
            dropdown(rec, "Lists!$F$2:$F$3", 7, rows);
            dropdown(rec, "Lists!$E$2:$E$" + (cats.length + 1), 11, rows);
            dropdown(rec, "Lists!$B$2:$B$" + (AssetType.values().length + 1), 13, rows);
            dropdown(rec, "Lists!$D$2:$D$" + (methods.length + 1), 19, rows);
            dropdown(exp, "Lists!$C$2:$C$" + (Expense.CATEGORIES.length + 1), 2, rows);
            int[] widths = {12, 11, 18, 16, 14, 15, 13, 9, 16, 12, 16, 18, 13, 16, 24, 12, 12, 11, 9, 13, 14, 24};
            for (int i = 0; i < widths.length; i++) rec.setColumnWidth(i, widths[i] * 256);
            int[] ew = {12, 11, 22, 30, 20, 11, 14};
            for (int i = 0; i < ew.length; i++) exp.setColumnWidth(i, ew[i] * 256);

            summarySheet(wb, st, b);

            Sheet acc = wb.createSheet("Accounts");
            header(acc, st, "Account No", "Client", "Phone", "Opening Fee Paid", "Asset Type", "Asset", "Cost", "Deposited",
                    "Status", "Loan Balance");
            int r = 1;
            for (AssetAccount a : accounts.findAllByOrderByOpenedDateDescIdDesc()) {
                if (!a.getBranch().getId().equals(branchId)) continue;
                List<Project> ps = projects.findByAccountIdOrderByIdAsc(a.getId());
                if (ps.isEmpty()) {
                    row(acc, st, r++, a.getNumberLabel(), a.getClient().getFullName(), a.getClient().getPhone(), a.getOpeningFeePaid(),
                            null, "(no project yet)", null, null, a.getStatusLabel(), null);
                }
                for (Project p : ps) {
                    row(acc, st, r++, a.getNumberLabel(), a.getClient().getFullName(), a.getClient().getPhone(), a.getOpeningFeePaid(),
                            p.getAssetType() == null ? null : p.getAssetType().name(), p.getAssetDescription(), p.getQuotationCost(),
                            p.getTotalDeposited(), p.getStatus().getLabel(), p.getLoanBalance());
                }
            }
            autosize(acc, 10);

            Sheet help = wb.createSheet("How to fill");
            String[] lines = {
                    "ZimFete asset finance return — " + b.getLabel() + " (branch code " + b.getCode() + ")",
                    "",
                    "1. RECEIPTS sheet: one row for every receipt you write, in the order of your receipt book.",
                    "   Date: dd/mm/yyyy.  Receipt No: the number on the receipt book (required; it stops the same receipt being captured twice).",
                    "   Type: JOINING_FEE ($10), SUBSCRIPTION ($1/month), ACCOUNT_OPENING ($50, can be paid in parts), ASSET_DEPOSIT, LOAN_REPAYMENT, OTHER_INCOME.",
                    "2. New client: on their first row fill First Name, Surname, National ID (if they have it with them), Phone, Gender, Village, Ward.",
                    "   Client from outside our branch areas (e.g. Harare): still use your branch's file, and write their district/town in District / Location.",
                    "   Later rows for the same client only need the Account No (or the National ID).",
                    "3. SACCO membership is for the veteran community only. JOINING_FEE and SUBSCRIPTION rows need the Category:",
                    "   WAR_VETERAN, WAR_COLLABORATOR, EX_DETAINEE (ex-political prisoner/detainee/restrictee), WIDOW, DESCENDANT.",
                    "   A client who only wants asset finance does NOT pay the joining fee or subscriptions.",
                    "4. ACCOUNT_OPENING: write the account number you issued (e.g. " + b.getCode() + "2641ME) or leave it blank and HQ will issue one.",
                    "   If the client pays the $50 in parts, one row per payment with the same Account No.",
                    "5. ASSET_DEPOSIT: Account No is required. For a new project put Asset Type, Asset Description, Quotation Cost and Target Date on the row.",
                    "   If the account has more than one project (e.g. borehole and fencing), fill Asset Type so HQ knows which project it is for.",
                    "6. LOAN_REPAYMENT: Account No (and Asset Type if the account has more than one loan).",
                    "7. EXPENDITURE sheet: one row per payment out, with the voucher number.",
                    "8. SUMMARY sheet: type the From and To dates; it adds up your receipts and expenditure for your daily, weekly or monthly WhatsApp report.",
                    "9. Send this file on the WhatsApp group (daily, weekly or monthly). Keep adding to the same file: rows HQ already has are skipped.",
                    "",
                    "Do not rename the sheets or the column headings. Do not use this file for another branch.",
                    "",
                    "Examples (do not type these into Receipts):",
                    "  05/10/2026 | 4901 | JOINING_FEE | Tendai | Moyo | 63-123456A75 | 0772000000 | Male | Chitate | Ward 7 | | WAR_VETERAN | | | | | | 10",
                    "  05/10/2026 | 4902 | ACCOUNT_OPENING | Rudo | Chari | | 0773000000 | Female | | Ward 9 | | | " + b.getCode() + "2641ME | BOREHOLE | 40m borehole | 1500 | 30/11/2026 | 20",
                    "  12/10/2026 | 4910 | ACCOUNT_OPENING | | | | | | | | | | " + b.getCode() + "2641ME | | | | | 30",
                    "  12/10/2026 | 4911 | ASSET_DEPOSIT | | | | | | | | | | " + b.getCode() + "2641ME | | | | | 400",
            };
            for (int i = 0; i < lines.length; i++) row(help, st, i, lines[i]);
            help.setColumnWidth(0, 150 * 256);
            wb.setSheetOrder("How to fill", 0);
            wb.setSheetOrder("Summary", 3);
            wb.setActiveSheet(1);
            wb.setSelectedTab(1);
            return bytes(wb);
        }
    }

    /** Formulas only: the clerk's own report for any period, worked out from what they typed. */
    private void summarySheet(XSSFWorkbook wb, Styles st, Branch b) {
        Sheet s = wb.createSheet("Summary");
        CellStyle bold = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        bold.setFont(f);
        row(s, st, 0, "ZimFete asset finance — " + b.getLabel() + " report");
        s.getRow(0).getCell(0).setCellStyle(bold);
        Row from = s.createRow(2);
        from.createCell(0).setCellValue("From");
        Cell fc = from.createCell(1);
        fc.setCellFormula("TODAY()");
        fc.setCellStyle(st.date);
        Row to = s.createRow(3);
        to.createCell(0).setCellValue("To");
        Cell tc = to.createCell(1);
        tc.setCellFormula("TODAY()");
        tc.setCellStyle(st.date);
        s.createRow(4).createCell(0).setCellValue("(type over the dates; for a daily report make From and To the same day)");

        String range = ",Receipts!$A$2:$A$5000,\">=\"&$B$3,Receipts!$A$2:$A$5000,\"<=\"&$B$4";
        int r = 6;
        Row h = s.createRow(r++);
        h.createCell(0).setCellValue("RECEIPTS");
        h.createCell(1).setCellValue("No.");
        h.createCell(2).setCellValue("Amount $");
        for (Cell c : h) c.setCellStyle(bold);
        int firstType = r;
        for (ReceiptType t : ReceiptType.values()) {
            Row row = s.createRow(r++);
            row.createCell(0).setCellValue(t.getLabel());
            row.createCell(1).setCellFormula("COUNTIFS(Receipts!$C$2:$C$5000,\"" + t.name() + "\"" + range + ")");
            Cell amt = row.createCell(2);
            amt.setCellFormula("SUMIFS(Receipts!$R$2:$R$5000,Receipts!$C$2:$C$5000,\"" + t.name() + "\"" + range + ")");
            amt.setCellStyle(st.money);
        }
        int lastType = r - 1;
        Row tot = s.createRow(r++);
        tot.createCell(0).setCellValue("TOTAL RECEIVED");
        Cell tr = tot.createCell(2);
        tr.setCellFormula("SUM(C" + (firstType + 1) + ":C" + (lastType + 1) + ")");
        tr.setCellStyle(st.money);
        tot.getCell(0).setCellStyle(bold);
        int totalRow = r;
        r++;
        Row eh = s.createRow(r++);
        eh.createCell(0).setCellValue("EXPENDITURE");
        eh.getCell(0).setCellStyle(bold);
        Row et = s.createRow(r++);
        et.createCell(0).setCellValue("Total spent");
        Cell ec = et.createCell(2);
        ec.setCellFormula("SUMIFS(Expenditure!$F$2:$F$5000,Expenditure!$A$2:$A$5000,\">=\"&$B$3,Expenditure!$A$2:$A$5000,\"<=\"&$B$4)");
        ec.setCellStyle(st.money);
        int spentRow = r;
        r++;
        Row net = s.createRow(r++);
        net.createCell(0).setCellValue("NET CASH (received − spent)");
        net.getCell(0).setCellStyle(bold);
        Cell nc = net.createCell(2);
        nc.setCellFormula("C" + totalRow + "-C" + spentRow);
        nc.setCellStyle(st.money);
        r++;
        Row nm = s.createRow(r++);
        nm.createCell(0).setCellValue("Opening fee receipts (count)");
        nm.createCell(1).setCellFormula("B" + (firstType + 1 + ReceiptType.ACCOUNT_OPENING.ordinal()));
        Row nj = s.createRow(r);
        nj.createCell(0).setCellValue("Joining fee receipts (new SACCO members)");
        nj.createCell(1).setCellFormula("B" + (firstType + 1 + ReceiptType.JOINING_FEE.ordinal()));
        s.setColumnWidth(0, 42 * 256);
        s.setColumnWidth(1, 14 * 256);
        s.setColumnWidth(2, 14 * 256);
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

    private static void dropdown(Sheet s, String formula, int column, int rows) {
        DataValidationHelper h = s.getDataValidationHelper();
        DataValidation dv = h.createValidation(h.createFormulaListConstraint(formula), new CellRangeAddressList(1, rows, column, column));
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
