package zw.co.zimfete.afs.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import zw.co.zimfete.afs.domain.*;

/**
 * The validated workbook a district asset finance clerk (AFC) keeps offline and sends on WhatsApp, and HQ imports
 * without retyping. Entries are checked as they are typed (dates, amounts, lists, repeated receipt numbers) and a
 * Check column says in plain words what is still wrong with each row. Headings and formulas are locked.
 */
final class ClerkWorkbook {
    static final int ROWS = 1500;
    private static final int LAST = ROWS + 1; // last Excel row number of the input area

    // Receipts columns (0-based), in the order of ExcelExportService.RETURN_RECEIPT_COLUMNS
    private static final int DATE = 0, RECEIPT_NO = 1, TYPE = 2, FIRST = 3, ID = 5, PHONE = 6, GENDER = 7,
            CATEGORY = 11, ACCOUNT = 12, ASSET_TYPE = 13, QUOTE = 15, TARGET = 16, AMOUNT = 17, MONTHS = 18, METHOD = 19,
            CHECK = 22;

    private static final String[] METHODS = {"Cash", "EcoCash", "Bank transfer", "Swipe", "InnBucks", "OneMoney"};

    private final Branch branch;
    private final List<Object[]> accountRows;
    private final XSSFWorkbook wb = new XSSFWorkbook();
    private final CellStyle head, input, inputDate, inputMoney, check, title, note, exampleText, exampleDate, exampleMoney,
            lockedDate, lockedMoney;

    /**
     * @param accountRows rows for the Accounts sheet: account no, client, phone, opening fee paid, asset type, asset,
     *                    cost, deposited, status, loan balance
     */
    ClerkWorkbook(Branch branch, List<Object[]> accountRows) {
        this.branch = branch;
        this.accountRows = accountRows;
        Font base = wb.getFontAt(0);
        base.setFontName("Arial");
        base.setFontHeightInPoints((short) 10);
        DataFormat fmt = wb.createDataFormat();

        Font bold = wb.createFont();
        bold.setFontName("Arial");
        bold.setBold(true);
        bold.setColor(IndexedColors.WHITE.getIndex());
        head = wb.createCellStyle();
        head.setFont(bold);
        head.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        head.setWrapText(true);
        head.setVerticalAlignment(VerticalAlignment.CENTER);

        input = unlocked(null);
        inputDate = unlocked(fmt.getFormat("dd/mm/yyyy"));
        inputMoney = unlocked(fmt.getFormat("#,##0.00"));

        Font checkFont = wb.createFont();
        checkFont.setFontName("Arial");
        checkFont.setBold(true);
        check = wb.createCellStyle();
        check.setFont(checkFont);
        check.setLocked(true);

        Font big = wb.createFont();
        big.setFontName("Arial");
        big.setBold(true);
        big.setFontHeightInPoints((short) 13);
        title = wb.createCellStyle();
        title.setFont(big);
        Font italic = wb.createFont();
        italic.setFontName("Arial");
        italic.setItalic(true);
        italic.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        note = wb.createCellStyle();
        note.setFont(italic);

        exampleText = wb.createCellStyle();
        exampleText.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        exampleText.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        exampleDate = wb.createCellStyle();
        exampleDate.cloneStyleFrom(exampleText);
        exampleDate.setDataFormat(fmt.getFormat("dd/mm/yyyy"));
        exampleMoney = wb.createCellStyle();
        exampleMoney.cloneStyleFrom(exampleText);
        exampleMoney.setDataFormat(fmt.getFormat("#,##0.00"));
        lockedDate = wb.createCellStyle();
        lockedDate.setDataFormat(fmt.getFormat("dd/mm/yyyy"));
        lockedMoney = wb.createCellStyle();
        lockedMoney.setDataFormat(fmt.getFormat("#,##0.00"));
    }

    byte[] build() throws IOException {
        howToFill();
        receipts();
        expenditure();
        summary();
        example();
        accounts();
        lists();
        wb.setActiveSheet(1);
        wb.setSelectedTab(1);
        wb.setForceFormulaRecalculation(true); // Check column and Summary are worked out when the file is opened
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return out.toByteArray();
    }

    // ------------------------------------------------------------------------------------------ sheets

    private void howToFill() {
        Sheet s = wb.createSheet("How to fill");
        String code = branch.getCode();
        String[] lines = {
                "ZimFete asset finance return — " + branch.getLabel() + " (branch code " + code + ")",
                "",
                "WHAT TO TYPE: only the white cells on the Receipts and Expenditure sheets. Headings, the Check column, the Summary and",
                "the Accounts list are filled in for you. Look at the Example sheet for how a row should look.",
                "",
                "1. RECEIPTS: one row for every receipt you write, in receipt-book order.",
                "   Date dd/mm/yyyy (not in the future). Receipt No from your receipt book (it cannot repeat in this file).",
                "   Type (pick from the list): JOINING_FEE $10 · SUBSCRIPTION $1/month · ACCOUNT_OPENING $50 (can be paid in parts) ·",
                "   ASSET_DEPOSIT · LOAN_REPAYMENT · OTHER_INCOME.",
                "2. NEW CLIENT: on their first row fill First Name, Surname, National ID (if they have it), Phone, Gender, Village, Ward.",
                "   From outside our branch areas (e.g. Harare)? Still use this file; write their town in District / Location.",
                "   Later rows for the same client need only the Account No.",
                "3. SACCO MEMBERSHIP is for the veteran community only. JOINING_FEE and SUBSCRIPTION rows need the Category:",
                "   WAR_VETERAN, WAR_COLLABORATOR, EX_DETAINEE (ex-political prisoner/detainee/restrictee), WIDOW, DESCENDANT.",
                "   Clients who only want asset finance do NOT pay the joining fee or subscriptions.",
                "4. ACCOUNT_OPENING: the account number you issued (e.g. " + code + "2641ME), or leave it blank for HQ to issue.",
                "   Part payments of the $50: one row per payment, same Account No.",
                "5. ASSET_DEPOSIT: Account No is required. New project? Add Asset Type, Asset Description, Quotation Cost, Target Date.",
                "   Account with more than one project (e.g. borehole and fencing)? Fill Asset Type so HQ knows which one.",
                "6. LOAN_REPAYMENT: Account No (and Asset Type if the account has more than one loan).",
                "7. EXPENDITURE: one row per payment out, with the voucher number.",
                "",
                "THE CHECK COLUMN (last column) turns red and says what is missing, e.g. \"Account No needed\". Fix every red row",
                "before you send. The Summary sheet shows how many rows still have problems.",
                "",
                "8. SUMMARY: type From and To dates to get your daily, weekly or monthly totals for the WhatsApp report.",
                "9. SEND this file on the WhatsApp group. Keep adding to the same file; rows HQ already has are skipped.",
                "",
                "Do not rename sheets or headings, insert columns, or use this file for another branch.",
        };
        for (int i = 0; i < lines.length; i++) {
            Cell c = s.createRow(i).createCell(0);
            c.setCellValue(lines[i]);
            if (i == 0) c.setCellStyle(title);
        }
        s.setColumnWidth(0, 125 * 256);
        lockSheet(s);
    }

    private void receipts() {
        XSSFSheet s = wb.createSheet("Receipts");
        String[] cols = Arrays.copyOf(ExcelExportService.RETURN_RECEIPT_COLUMNS, ExcelExportService.RETURN_RECEIPT_COLUMNS.length + 1);
        cols[CHECK] = "Check";
        header(s, cols);
        for (int r = 1; r <= ROWS; r++) {
            Row row = s.createRow(r);
            for (int c = 0; c < CHECK; c++) {
                row.createCell(c).setCellStyle(c == DATE || c == TARGET ? inputDate : c == QUOTE || c == AMOUNT ? inputMoney : input);
            }
            Cell ck = row.createCell(CHECK);
            ck.setCellFormula(receiptCheck(r + 1));
            ck.setCellStyle(check);
        }
        int[] widths = {12, 11, 18, 15, 14, 15, 13, 9, 15, 11, 16, 18, 13, 16, 22, 12, 12, 11, 9, 13, 14, 20, 34};
        for (int i = 0; i < widths.length; i++) s.setColumnWidth(i, widths[i] * 256);
        s.createFreezePane(3, 1);

        DataValidationHelper h = s.getDataValidationHelper();
        date(s, h, DATE, "Date", true);
        date(s, h, TARGET, "Target date", false);
        custom(s, h, RECEIPT_NO, "AND(LEN(B2)<=20,COUNTIF($B$2:$B$" + LAST + ",B2)=1)", "Receipt No",
                "This receipt number is already in the file. Each receipt number can be used once.");
        list(s, h, TYPE, "Lists!$A$2:$A$" + (ReceiptType.values().length + 1), "Type", "Pick the type from the list.");
        list(s, h, GENDER, "Lists!$F$2:$F$3", "Gender", "Pick Female or Male.");
        list(s, h, CATEGORY, "Lists!$E$2:$E$" + (veteranCategories().length + 1), "Category",
                "Pick the veteran category from the list (members only).");
        list(s, h, ASSET_TYPE, "Lists!$B$2:$B$" + (AssetType.values().length + 1), "Asset type", "Pick the asset type from the list.");
        list(s, h, METHOD, "Lists!$D$2:$D$" + (METHODS.length + 1), "Payment method", "Pick the payment method from the list.");
        decimal(s, h, AMOUNT, "0.01", "100000", "Amount", "Amount must be a number greater than 0 (no $ sign).");
        decimal(s, h, QUOTE, "1", "1000000", "Quotation cost", "Quotation cost must be a number (no $ sign).");
        DataValidation months = h.createValidation(h.createIntegerConstraint(DataValidationConstraint.OperatorType.BETWEEN, "1", "120"),
                range(MONTHS));
        stop(months, "Months", "Months must be a whole number from 1 to 120.");
        s.addValidationData(months);
        custom(s, h, ID, "LEN(F2)<=20", "National ID", "National ID is too long.");
        custom(s, h, PHONE, "LEN(G2)<=20", "Phone", "Phone number is too long.");
        custom(s, h, ACCOUNT, "LEN(M2)<=20", "Account No", "Account number is too long.");
        custom(s, h, FIRST, "LEN(D2)<=60", "First name", "Name is too long.");

        redWhenProblem(s, "W", CHECK);
        s.setAutoFilter(new CellRangeAddress(0, ROWS, 0, CHECK));
        lockSheet(s);
    }

    /** Plain-words check of one receipts row (Excel row n). */
    private static String receiptCheck(int n) {
        String t = "$C" + n, amt = "$R" + n, acc = "$M" + n;
        String accountKnown = "OR(COUNTIF(Accounts!$A:$A," + acc + ")>0,COUNTIFS($M$2:$M$" + LAST + "," + acc + ",$C$2:$C$" + LAST
                + ",\"ACCOUNT_OPENING\")>0)";
        return "IF(COUNTA($A" + n + ":$V" + n + ")=0,\"\","
                + "IF($A" + n + "=\"\",\"Date missing\","
                + "IF($B" + n + "=\"\",\"Receipt No missing\","
                + "IF(" + t + "=\"\",\"Type missing\","
                + "IF(" + amt + "=\"\",\"Amount missing\","
                + "IF(AND(OR(" + t + "=\"ASSET_DEPOSIT\"," + t + "=\"LOAN_REPAYMENT\")," + acc + "=\"\"),\"Account No needed\","
                + "IF(AND(OR(" + t + "=\"JOINING_FEE\"," + t + "=\"SUBSCRIPTION\"),$L" + n + "=\"\"),\"Category needed (members only)\","
                + "IF(AND(" + t + "=\"JOINING_FEE\"," + amt + "<>10),\"Joining fee is $10\","
                + "IF(AND(" + t + "=\"ACCOUNT_OPENING\"," + amt + ">50),\"Opening fee is at most $50\","
                + "IF(AND(" + acc + "=\"\",$D" + n + "=\"\",$F" + n + "=\"\"),\"Name or Account No needed\","
                + "IF(AND(OR(" + t + "=\"ASSET_DEPOSIT\"," + t + "=\"LOAN_REPAYMENT\"),NOT(" + accountKnown + ")),"
                + "\"Account No not on the Accounts list: check it\","
                + "\"OK\")))))))))))";
    }

    private void expenditure() {
        XSSFSheet s = wb.createSheet("Expenditure");
        String[] cols = Arrays.copyOf(ExcelExportService.RETURN_EXPENSE_COLUMNS, ExcelExportService.RETURN_EXPENSE_COLUMNS.length + 1);
        cols[7] = "Check";
        header(s, cols);
        for (int r = 1; r <= ROWS; r++) {
            Row row = s.createRow(r);
            for (int c = 0; c < 7; c++) row.createCell(c).setCellStyle(c == 0 ? inputDate : c == 5 ? inputMoney : input);
            int n = r + 1;
            Cell ck = row.createCell(7);
            ck.setCellFormula("IF(COUNTA($A" + n + ":$G" + n + ")=0,\"\",IF($A" + n + "=\"\",\"Date missing\",IF($B" + n
                    + "=\"\",\"Voucher No missing\",IF($C" + n + "=\"\",\"Category missing\",IF($F" + n + "=\"\",\"Amount missing\",\"OK\")))))");
            ck.setCellStyle(check);
        }
        int[] widths = {12, 11, 22, 30, 20, 11, 14, 22};
        for (int i = 0; i < widths.length; i++) s.setColumnWidth(i, widths[i] * 256);
        s.createFreezePane(0, 1);
        DataValidationHelper h = s.getDataValidationHelper();
        date(s, h, 0, "Date", true);
        list(s, h, 2, "Lists!$C$2:$C$" + (Expense.CATEGORIES.length + 1), "Category", "Pick the category from the list.");
        decimal(s, h, 5, "0.01", "100000", "Amount", "Amount must be a number greater than 0 (no $ sign).");
        custom(s, h, 1, "LEN(B2)<=20", "Voucher No", "Voucher number is too long.");
        redWhenProblem(s, "H", 7);
        lockSheet(s);
    }

    /** The clerk's report for any period, worked out from what they typed. */
    private void summary() {
        Sheet s = wb.createSheet("Summary");
        Cell t = s.createRow(0).createCell(0);
        t.setCellValue("ZimFete asset finance — " + branch.getLabel() + " report");
        t.setCellStyle(title);
        Row from = s.createRow(2);
        from.createCell(0).setCellValue("From");
        Cell fc = from.createCell(1);
        fc.setCellFormula("TODAY()");
        fc.setCellStyle(inputDate);
        Row to = s.createRow(3);
        to.createCell(0).setCellValue("To");
        Cell tc = to.createCell(1);
        tc.setCellFormula("TODAY()");
        tc.setCellStyle(inputDate);
        Cell hint = s.createRow(4).createCell(0);
        hint.setCellValue("Type over the two dates. For a daily report make From and To the same day.");
        hint.setCellStyle(note);

        String period = ",Receipts!$A$2:$A$" + LAST + ",\">=\"&$B$3,Receipts!$A$2:$A$" + LAST + ",\"<=\"&$B$4";
        int r = 6;
        Row h = s.createRow(r++);
        String[] hh = {"RECEIPTS", "No.", "Amount $"};
        for (int i = 0; i < hh.length; i++) {
            h.createCell(i).setCellValue(hh[i]);
            h.getCell(i).setCellStyle(head);
        }
        int first = r;
        for (ReceiptType rt : ReceiptType.values()) {
            Row row = s.createRow(r++);
            row.createCell(0).setCellValue(rt.getLabel());
            row.createCell(1).setCellFormula("COUNTIFS(Receipts!$C$2:$C$" + LAST + ",\"" + rt.name() + "\"" + period + ")");
            Cell amt = row.createCell(2);
            amt.setCellFormula("SUMIFS(Receipts!$R$2:$R$" + LAST + ",Receipts!$C$2:$C$" + LAST + ",\"" + rt.name() + "\"" + period + ")");
            amt.setCellStyle(lockedMoney);
        }
        Row tot = s.createRow(r++);
        tot.createCell(0).setCellValue("TOTAL RECEIVED");
        Cell tr = tot.createCell(2);
        tr.setCellFormula("SUM(C" + (first + 1) + ":C" + (r - 1) + ")"); // the receipt-type rows above, not this row
        tr.setCellStyle(lockedMoney);
        int totalRow = r;
        r++;
        Row spent = s.createRow(r++);
        spent.createCell(0).setCellValue("TOTAL SPENT (Expenditure)");
        Cell sc = spent.createCell(2);
        sc.setCellFormula("SUMIFS(Expenditure!$F$2:$F$" + LAST + ",Expenditure!$A$2:$A$" + LAST + ",\">=\"&$B$3,Expenditure!$A$2:$A$"
                + LAST + ",\"<=\"&$B$4)");
        sc.setCellStyle(lockedMoney);
        int spentRow = r;
        Row net = s.createRow(r++);
        net.createCell(0).setCellValue("NET CASH (received − spent)");
        Cell nc = net.createCell(2);
        nc.setCellFormula("C" + totalRow + "-C" + spentRow);
        nc.setCellStyle(lockedMoney);
        r++;
        Row fees = s.createRow(r++);
        fees.createCell(0).setCellValue("Opening fee receipts (count)");
        fees.createCell(1).setCellFormula("B" + (first + 1 + ReceiptType.ACCOUNT_OPENING.ordinal()));
        Row joins = s.createRow(r++);
        joins.createCell(0).setCellValue("Joining fee receipts (new SACCO members)");
        joins.createCell(1).setCellFormula("B" + (first + 1 + ReceiptType.JOINING_FEE.ordinal()));
        r++;
        Row prob = s.createRow(r++);
        prob.createCell(0).setCellValue("ROWS WITH PROBLEMS — fix before sending (whole file)");
        prob.getCell(0).setCellStyle(check);
        prob.createCell(1).setCellFormula("SUMPRODUCT((Receipts!$W$2:$W$" + LAST + "<>\"\")*(Receipts!$W$2:$W$" + LAST
                + "<>\"OK\"))+SUMPRODUCT((Expenditure!$H$2:$H$" + LAST + "<>\"\")*(Expenditure!$H$2:$H$" + LAST + "<>\"OK\"))");
        prob.getCell(1).setCellStyle(check);
        SheetConditionalFormatting cf = s.getSheetConditionalFormatting();
        ConditionalFormattingRule bad = cf.createConditionalFormattingRule(ComparisonOperator.GT, "0");
        bad.createPatternFormatting().setFillBackgroundColor(IndexedColors.ROSE.getIndex());
        cf.addConditionalFormatting(new CellRangeAddress[] {new CellRangeAddress(r - 1, r - 1, 1, 1)}, bad);

        s.setColumnWidth(0, 48 * 256);
        s.setColumnWidth(1, 14 * 256);
        s.setColumnWidth(2, 14 * 256);
        DataValidationHelper dh = s.getDataValidationHelper();
        DataValidation dv = dh.createValidation(dh.createDateConstraint(DataValidationConstraint.OperatorType.BETWEEN,
                "DATE(2024,1,1)", "DATE(2100,12,31)", "dd/mm/yyyy"), new CellRangeAddressList(2, 3, 1, 1));
        stop(dv, "Date", "Type a date as dd/mm/yyyy.");
        s.addValidationData(dv);
        lockSheet(s);
    }

    /** A filled-in example, in the same columns, that is not imported. */
    private void example() {
        Sheet s = wb.createSheet("Example");
        String code = branch.getCode();
        Cell t = s.createRow(0).createCell(0);
        t.setCellValue("Example rows (for reference only; this sheet is not imported). Type your own rows on the Receipts sheet.");
        t.setCellStyle(note);
        Row h = s.createRow(1);
        String[] cols = ExcelExportService.RETURN_RECEIPT_COLUMNS;
        for (int i = 0; i < cols.length; i++) {
            h.createCell(i).setCellValue(cols[i]);
            h.getCell(i).setCellStyle(head);
        }
        Object[][] rows = {
                {d(5), "4901", "JOINING_FEE", "Tendai", "Moyo", "63-123456A75", "0772000000", "Male", "Chitate", "Ward 7", null,
                        "WAR_VETERAN", null, null, null, null, null, 10, null, "Cash", "AFC name", "New SACCO member"},
                {d(5), "4902", "SUBSCRIPTION", null, null, "63-123456A75", null, null, null, null, null, "WAR_VETERAN", null, null,
                        null, null, null, 3, 3, "Cash", "AFC name", "3 months"},
                {d(5), "4903", "ACCOUNT_OPENING", "Rudo", "Chari", null, "0773000000", "Female", "Plot 4", "Ward 9", null, null,
                        code + "2641ME", "BOREHOLE", "40m borehole + pump", 1500, d(60), 20, null, "EcoCash", "AFC name", "Part payment"},
                {d(12), "4910", "ACCOUNT_OPENING", null, null, null, null, null, null, null, null, null, code + "2641ME", null, null,
                        null, null, 30, null, "Cash", "AFC name", "Balance of $50"},
                {d(12), "4911", "ASSET_DEPOSIT", null, null, null, null, null, null, null, null, null, code + "2641ME", null, null,
                        null, null, 400, null, "Cash", "AFC name", null},
                {d(12), "4912", "ACCOUNT_OPENING", "Farai", "Banda", null, "0774000000", "Male", "Banket", null, "Harare", null, null,
                        "SOLAR", "Solar for home", 900, null, 50, null, "Cash", "AFC name", "Client from outside our areas"},
        };
        for (int i = 0; i < rows.length; i++) {
            Row row = s.createRow(i + 2);
            for (int c = 0; c < rows[i].length; c++) {
                Object v = rows[i][c];
                Cell cell = row.createCell(c);
                if (v instanceof java.time.LocalDate ld) {
                    cell.setCellValue(ld);
                    cell.setCellStyle(exampleDate);
                } else if (v instanceof Number num) {
                    cell.setCellValue(num.doubleValue());
                    cell.setCellStyle(c == AMOUNT || c == QUOTE ? exampleMoney : exampleText);
                } else {
                    if (v != null) cell.setCellValue(v.toString());
                    cell.setCellStyle(exampleText);
                }
            }
        }
        Row eh = s.createRow(rows.length + 4);
        String[] ecols = ExcelExportService.RETURN_EXPENSE_COLUMNS;
        for (int i = 0; i < ecols.length; i++) {
            eh.createCell(i).setCellValue(ecols[i]);
            eh.getCell(i).setCellStyle(head);
        }
        Object[] e = {d(12), "V031", "Airtime & travel", "Airtime for client follow-ups", "Econet", 1, "AFC name"};
        Row er = s.createRow(rows.length + 5);
        for (int c = 0; c < e.length; c++) {
            Cell cell = er.createCell(c);
            if (e[c] instanceof java.time.LocalDate ld) {
                cell.setCellValue(ld);
                cell.setCellStyle(exampleDate);
            } else if (e[c] instanceof Number num) {
                cell.setCellValue(num.doubleValue());
                cell.setCellStyle(exampleMoney);
            } else {
                cell.setCellValue(e[c].toString());
                cell.setCellStyle(exampleText);
            }
        }
        int[] widths = {12, 11, 18, 15, 14, 15, 13, 9, 15, 11, 16, 18, 13, 16, 22, 12, 12, 11, 9, 13, 14, 26};
        for (int i = 0; i < widths.length; i++) s.setColumnWidth(i, widths[i] * 256);
        lockSheet(s);
    }

    private static java.time.LocalDate d(int day) {
        java.time.LocalDate base = java.time.LocalDate.of(2026, 10, 1);
        return day <= 31 ? base.withDayOfMonth(day) : base.plusDays(day);
    }

    private void accounts() {
        Sheet s = wb.createSheet("Accounts");
        header(s, new String[] {"Account No", "Client", "Phone", "Opening Fee Paid", "Asset Type", "Asset", "Cost", "Deposited",
                "Status", "Loan Balance"});
        int r = 1;
        for (Object[] a : accountRows) {
            Row row = s.createRow(r++);
            for (int c = 0; c < a.length; c++) {
                Object v = a[c];
                if (v == null) continue;
                Cell cell = row.createCell(c);
                if (v instanceof BigDecimal bd) {
                    cell.setCellValue(bd.doubleValue());
                    cell.setCellStyle(lockedMoney);
                } else {
                    cell.setCellValue(v.toString());
                }
            }
        }
        int[] widths = {13, 30, 13, 12, 14, 26, 11, 11, 30, 12};
        for (int i = 0; i < widths.length; i++) s.setColumnWidth(i, widths[i] * 256);
        s.createFreezePane(0, 1);
        s.setAutoFilter(new CellRangeAddress(0, Math.max(1, r - 1), 0, 9));
        lockSheet(s);
    }

    private void lists() {
        Sheet s = wb.createSheet(ExcelExportService.BRANCH_CELL_SHEET);
        String[] heads = {"Receipt types", "Asset types", "Expense categories", "Payment methods", "Categories", "Gender", "Branch"};
        Row h = s.createRow(0);
        for (int i = 0; i < heads.length; i++) h.createCell(i).setCellValue(heads[i]);
        MemberCategory[] cats = veteranCategories();
        String[][] cols = {
                Arrays.stream(ReceiptType.values()).map(Enum::name).toArray(String[]::new),
                Arrays.stream(AssetType.values()).map(Enum::name).toArray(String[]::new),
                Expense.CATEGORIES, METHODS,
                Arrays.stream(cats).map(Enum::name).toArray(String[]::new),
                {"Female", "Male"}, {branch.getCode()}};
        for (int c = 0; c < cols.length; c++) {
            for (int i = 0; i < cols[c].length; i++) {
                Row row = s.getRow(i + 1) != null ? s.getRow(i + 1) : s.createRow(i + 1);
                row.createCell(c).setCellValue(cols[c][i]);
            }
        }
        for (int i = 0; i < heads.length; i++) s.setColumnWidth(i, 30 * 256);
        lockSheet(s);
        wb.setSheetHidden(wb.getSheetIndex(s), true);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static MemberCategory[] veteranCategories() {
        return Arrays.stream(MemberCategory.values()).filter(MemberCategory::isVeteranCommunity).toArray(MemberCategory[]::new);
    }

    private CellStyle unlocked(Short format) {
        CellStyle st = wb.createCellStyle();
        st.setLocked(false);
        st.setBorderBottom(BorderStyle.HAIR);
        st.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        st.setBorderRight(BorderStyle.HAIR);
        st.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        if (format != null) st.setDataFormat(format);
        return st;
    }

    private void header(Sheet s, String[] cols) {
        Row h = s.createRow(0);
        h.setHeightInPoints(30);
        for (int i = 0; i < cols.length; i++) {
            Cell c = h.createCell(i);
            c.setCellValue(cols[i]);
            c.setCellStyle(head);
        }
    }

    /** Protects the sheet (no password) so headings and formulas cannot be changed; input cells stay editable. */
    private static void lockSheet(Sheet sheet) {
        XSSFSheet s = (XSSFSheet) sheet;
        s.lockFormatColumns(false);
        s.lockFormatRows(false);
        s.lockAutoFilter(false);
        s.lockSort(true);
        s.lockInsertRows(true);
        s.lockDeleteRows(true);
        s.enableLocking();
    }

    private static CellRangeAddressList range(int col) {
        return new CellRangeAddressList(1, ROWS, col, col);
    }

    private static void stop(DataValidation dv, String title, String message) {
        dv.setErrorStyle(DataValidation.ErrorStyle.STOP);
        dv.setShowErrorBox(true);
        dv.createErrorBox(title, message);
        dv.setEmptyCellAllowed(true);
    }

    private static void date(Sheet s, DataValidationHelper h, int col, String title, boolean notFuture) {
        DataValidation dv = h.createValidation(h.createDateConstraint(DataValidationConstraint.OperatorType.BETWEEN,
                "DATE(2024,1,1)", notFuture ? "TODAY()" : "DATE(2100,12,31)", "dd/mm/yyyy"), range(col));
        stop(dv, title, notFuture ? "Type a date as dd/mm/yyyy. It cannot be in the future." : "Type a date as dd/mm/yyyy.");
        dv.createPromptBox(title, "dd/mm/yyyy");
        dv.setShowPromptBox(true);
        s.addValidationData(dv);
    }

    private static void list(Sheet s, DataValidationHelper h, int col, String formula, String title, String message) {
        DataValidation dv = h.createValidation(h.createFormulaListConstraint(formula), range(col));
        stop(dv, title, message);
        s.addValidationData(dv);
    }

    private static void decimal(Sheet s, DataValidationHelper h, int col, String min, String max, String title, String message) {
        DataValidation dv = h.createValidation(h.createDecimalConstraint(DataValidationConstraint.OperatorType.BETWEEN, min, max), range(col));
        stop(dv, title, message);
        s.addValidationData(dv);
    }

    private static void custom(Sheet s, DataValidationHelper h, int col, String formula, String title, String message) {
        DataValidation dv = h.createValidation(h.createCustomConstraint(formula), range(col));
        stop(dv, title, message);
        s.addValidationData(dv);
    }

    /** Check column: green when OK, red with the message otherwise. */
    private static void redWhenProblem(Sheet s, String colLetter, int col) {
        SheetConditionalFormatting cf = s.getSheetConditionalFormatting();
        ConditionalFormattingRule bad = cf.createConditionalFormattingRule("AND(" + colLetter + "2<>\"\"," + colLetter + "2<>\"OK\")");
        bad.createPatternFormatting().setFillBackgroundColor(IndexedColors.ROSE.getIndex());
        bad.createFontFormatting().setFontColorIndex(IndexedColors.DARK_RED.getIndex());
        ConditionalFormattingRule ok = cf.createConditionalFormattingRule(colLetter + "2=\"OK\"");
        ok.createFontFormatting().setFontColorIndex(IndexedColors.GREEN.getIndex());
        cf.addConditionalFormatting(new CellRangeAddress[] {new CellRangeAddress(1, ROWS, col, col)}, bad, ok);
    }
}
