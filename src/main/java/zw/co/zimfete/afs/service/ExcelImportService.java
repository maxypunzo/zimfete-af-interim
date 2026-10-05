package zw.co.zimfete.afs.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.*;

/**
 * Reads a district return (see {@link ExcelExportService#districtReturnTemplate}) and posts every row through
 * the same services as manual capture. Each row is its own transaction; rows whose receipt / voucher number
 * is already on file are skipped, so a clerk re-sending yesterday's rows does no harm.
 */
@Service
public class ExcelImportService {
    public enum Outcome { POSTED, SKIPPED, ERROR }

    public record RowResult(String sheet, int row, Outcome outcome, String message) {
    }

    public record ImportResult(List<RowResult> rows) {
        public long count(Outcome o) {
            return rows.stream().filter(r -> r.outcome() == o).count();
        }

        public long getPosted() { return count(Outcome.POSTED); }
        public long getSkipped() { return count(Outcome.SKIPPED); }
        public long getErrors() { return count(Outcome.ERROR); }
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d/M/yyyy"), DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("d.M.yyyy"), DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d/M/yy"));

    private final BranchRepository branches;
    private final MemberRepository members;
    private final AssetAccountRepository accounts;
    private final ReceiptRepository receipts;
    private final ExpenseRepository expenses;
    private final MemberService memberService;
    private final AccountService accountService;
    private final ReceiptService receiptService;
    private final TransactionTemplate tx;
    private final DataFormatter formatter = new DataFormatter();

    public ExcelImportService(BranchRepository branches, MemberRepository members, AssetAccountRepository accounts,
                              ReceiptRepository receipts, ExpenseRepository expenses, MemberService memberService,
                              AccountService accountService, ReceiptService receiptService, PlatformTransactionManager tm) {
        this.branches = branches;
        this.members = members;
        this.accounts = accounts;
        this.receipts = receipts;
        this.expenses = expenses;
        this.memberService = memberService;
        this.accountService = accountService;
        this.receiptService = receiptService;
        this.tx = new TransactionTemplate(tm);
    }

    public ImportResult importReturn(InputStream in, Long branchId) throws IOException {
        Branch branch = branches.findById(branchId).orElseThrow(() -> new BusinessException("Choose the branch the return is from."));
        List<RowResult> results = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet rec = wb.getSheet("Receipts");
            Sheet exp = wb.getSheet("Expenditure");
            if (rec == null && exp == null) throw new BusinessException("No 'Receipts' or 'Expenditure' sheet found. Use the district return template.");
            if (rec != null) {
                Map<String, Integer> cols = columns(rec);
                // oldest first so members and accounts exist before their later receipts
                List<Row> rows = dataRows(rec);
                rows.sort(Comparator.comparing((Row r) -> Optional.ofNullable(safeDate(r, cols.get("date"))).orElse(LocalDate.MAX))
                        .thenComparing(r -> typeOrder(text(r, cols.get("type")))));
                for (Row r : rows) results.add(run("Receipts", r, () -> receiptRow(branch, r, cols)));
            }
            if (exp != null) {
                Map<String, Integer> cols = columns(exp);
                for (Row r : dataRows(exp)) results.add(run("Expenditure", r, () -> expenseRow(branch, r, cols)));
            }
        }
        results.sort(Comparator.comparing(RowResult::sheet).reversed().thenComparing(RowResult::row));
        return new ImportResult(results);
    }

    private RowResult run(String sheet, Row r, java.util.function.Supplier<RowResult> work) {
        try {
            RowResult res = tx.execute(s -> work.get());
            return res != null ? res : new RowResult(sheet, r.getRowNum() + 1, Outcome.ERROR, "No result");
        } catch (BusinessException e) {
            return new RowResult(sheet, r.getRowNum() + 1, Outcome.ERROR, e.getMessage());
        } catch (RuntimeException e) {
            return new RowResult(sheet, r.getRowNum() + 1, Outcome.ERROR, "Could not post: " + e.getMessage());
        }
    }

    private RowResult receiptRow(Branch branch, Row row, Map<String, Integer> c) {
        int n = row.getRowNum() + 1;
        LocalDate date = date(row, c.get("date"));
        String receiptNo = text(row, c.get("receipt no"));
        ReceiptType type = ReceiptType.parse(text(row, c.get("type")));
        BigDecimal amount = amount(row, c.get("amount"));
        if (date == null) throw new BusinessException("Date is missing or not a date.");
        if (receiptNo == null) throw new BusinessException("Receipt No is required.");
        if (type == null) throw new BusinessException("Type '" + text(row, c.get("type")) + "' is not recognised.");
        if (receipts.existsByBranchIdAndReceiptNoIgnoreCase(branch.getId(), receiptNo)) {
            return new RowResult("Receipts", n, Outcome.SKIPPED, "Receipt " + receiptNo + " already captured.");
        }
        String clerk = text(row, c.get("clerk"));
        String method = Optional.ofNullable(text(row, c.get("payment method"))).orElse("Cash");

        // --- find or create the member
        String nid = MemberService.normaliseId(text(row, c.get("national id")));
        String accountNo = upper(text(row, c.get("account no")));
        Member member = null;
        if (nid != null) member = members.findByNationalIdIgnoreCase(nid).orElse(null);
        AssetAccount account = accountNo == null ? null : accounts.findByAccountNoIgnoreCase(accountNo).orElse(null);
        if (member == null && account != null) member = account.getMember();
        boolean created = false;
        if (member == null) {
            if (nid == null) throw new BusinessException("National ID is required for a member's first receipt.");
            RegistrationRequest reg = new RegistrationRequest();
            reg.setBranchId(branch.getId());
            reg.setFirstName(text(row, c.get("first name")));
            reg.setSurname(text(row, c.get("surname")));
            reg.setNationalId(nid);
            reg.setPhone(text(row, c.get("phone")));
            reg.setGender(text(row, c.get("gender")));
            reg.setVillage(text(row, c.get("village")));
            reg.setWard(text(row, c.get("ward")));
            reg.setDateJoined(date);
            reg.setCapturedBy(clerk);
            reg.setPayJoiningFee(false);
            reg.setSubsMonths(0);
            if (reg.getFirstName() == null || reg.getSurname() == null) {
                throw new BusinessException("ID " + nid + " is new: First Name and Surname are needed to register them.");
            }
            member = memberService.register(reg, "IMPORT");
            created = true;
        }

        String prefix = created ? "New member " + member.getMemberNo() + ". " : "";
        if (type == ReceiptType.ACCOUNT_OPENING) {
            if (account != null) throw new BusinessException("Account " + accountNo + " already exists.");
            AccountRequest ar = new AccountRequest();
            ar.setAccountNo(accountNo);
            ar.setOpenedDate(date);
            ar.setOpenedBy(clerk);
            ar.setAssetType(AssetType.parse(text(row, c.get("asset type"))));
            ar.setAssetDescription(text(row, c.get("asset description")));
            ar.setQuotationCost(amount(row, c.get("quotation cost")));
            ar.setTargetDate(safeDate(row, c.get("target date")));
            ar.setNotes(text(row, c.get("notes")));
            ar.setOpeningReceiptNo(receiptNo);
            ar.setPaymentMethod(method);
            AssetAccount opened = accountService.open(member.getId(), ar, "IMPORT");
            return new RowResult("Receipts", n, Outcome.POSTED, prefix + "Opened account " + opened.getAccountNo() + " for " + member.getFullName() + ".");
        }

        if (amount == null || amount.signum() <= 0) throw new BusinessException("Amount is missing.");
        ReceiptRequest req = new ReceiptRequest();
        req.setBranchId(branch.getId());
        req.setReceiptDate(date);
        req.setType(type);
        req.setMemberId(member.getId());
        req.setAmount(amount);
        req.setReceiptNo(receiptNo);
        req.setPaymentMethod(method);
        req.setCapturedBy(clerk);
        req.setDescription(text(row, c.get("notes")));
        req.setSource("IMPORT");
        BigDecimal months = amount(row, c.get("months (subs)"));
        if (months != null) req.setMonths(months.intValue());

        if (type.needsAccount()) {
            if (account == null) account = onlyActiveAccount(member, accountNo);
            if (!account.getMember().getId().equals(member.getId())) {
                throw new BusinessException("Account " + account.getAccountNo() + " belongs to " + account.getMember().getFullName() + ", not ID " + nid + ".");
            }
            fillMissingAssetDetails(account, row, c);
            req.setAccountId(account.getId());
        }
        Receipt saved = receiptService.record(req);
        return new RowResult("Receipts", n, Outcome.POSTED, prefix + saved.getType().getLabel() + " $" + saved.getAmount()
                + " for " + member.getFullName() + (saved.getAccount() != null ? " on " + saved.getAccount().getAccountNo() : "") + ".");
    }

    private AssetAccount onlyActiveAccount(Member m, String accountNo) {
        if (accountNo != null) throw new BusinessException("Account " + accountNo + " not found.");
        List<AssetAccount> open = accounts.findByMemberIdOrderByOpenedDateDesc(m.getId()).stream()
                .filter(a -> a.getStatus() != ProjectStatus.CANCELLED).toList();
        if (open.size() == 1) return open.get(0);
        throw new BusinessException(open.isEmpty() ? m.getFullName() + " has no asset finance account yet."
                : m.getFullName() + " has " + open.size() + " accounts: fill in Account No.");
    }

    private void fillMissingAssetDetails(AssetAccount a, Row row, Map<String, Integer> c) {
        if (a.isLoanStarted()) return;
        boolean changed = false;
        if (a.getAssetType() == null && text(row, c.get("asset type")) != null) {
            a.setAssetType(AssetType.parse(text(row, c.get("asset type"))));
            changed = true;
        }
        if (a.getAssetDescription() == null && text(row, c.get("asset description")) != null) {
            a.setAssetDescription(text(row, c.get("asset description")));
            changed = true;
        }
        if (a.getQuotationCost() == null && amount(row, c.get("quotation cost")) != null) {
            a.setQuotationCost(amount(row, c.get("quotation cost")));
            changed = true;
        }
        if (a.getTargetDate() == null && safeDate(row, c.get("target date")) != null) {
            a.setTargetDate(safeDate(row, c.get("target date")));
            changed = true;
        }
        if (changed) accounts.save(a);
    }

    private RowResult expenseRow(Branch branch, Row row, Map<String, Integer> c) {
        int n = row.getRowNum() + 1;
        LocalDate date = date(row, c.get("date"));
        BigDecimal amount = amount(row, c.get("amount"));
        String voucher = text(row, c.get("voucher no"));
        if (date == null) throw new BusinessException("Date is missing or not a date.");
        if (amount == null || amount.signum() <= 0) throw new BusinessException("Amount is missing.");
        if (voucher == null) throw new BusinessException("Voucher No is required.");
        if (expenses.existsByBranchIdAndVoucherNoIgnoreCaseAndExpenseDate(branch.getId(), voucher, date)) {
            return new RowResult("Expenditure", n, Outcome.SKIPPED, "Voucher " + voucher + " already captured.");
        }
        Expense e = new Expense();
        e.setBranch(branch);
        e.setExpenseDate(date);
        e.setVoucherNo(voucher);
        e.setCategory(Optional.ofNullable(text(row, c.get("category"))).orElse("Other"));
        e.setDescription(text(row, c.get("description")));
        e.setPayee(text(row, c.get("payee")));
        e.setAmount(amount);
        e.setCapturedBy(text(row, c.get("clerk")));
        e.setSource("IMPORT");
        expenses.save(e);
        return new RowResult("Expenditure", n, Outcome.POSTED, e.getCategory() + " $" + amount + ".");
    }

    // ---------------------------------------------------------------- cell reading

    private Map<String, Integer> columns(Sheet s) {
        Map<String, Integer> m = new HashMap<>();
        Row h = s.getRow(0);
        if (h == null) return m;
        for (Cell cell : h) {
            String t = formatter.formatCellValue(cell).trim().toLowerCase();
            if (!t.isEmpty()) m.put(t, cell.getColumnIndex());
        }
        return m;
    }

    private List<Row> dataRows(Sheet s) {
        List<Row> rows = new ArrayList<>();
        for (Row r : s) {
            if (r.getRowNum() == 0) continue;
            boolean any = false;
            for (Cell cell : r) {
                if (!formatter.formatCellValue(cell).isBlank()) {
                    any = true;
                    break;
                }
            }
            if (any) rows.add(r);
        }
        return rows;
    }

    private static int typeOrder(String type) {
        ReceiptType t = ReceiptType.parse(type);
        return t == null ? 99 : t.ordinal();
    }

    private String text(Row r, Integer col) {
        if (col == null) return null;
        Cell c = r.getCell(col);
        if (c == null) return null;
        String s = formatter.formatCellValue(c).trim();
        return s.isEmpty() ? null : s;
    }

    private BigDecimal amount(Row r, Integer col) {
        if (col == null) return null;
        Cell c = r.getCell(col);
        if (c == null) return null;
        if (c.getCellType() == CellType.NUMERIC || (c.getCellType() == CellType.FORMULA && c.getCachedFormulaResultType() == CellType.NUMERIC)) {
            return BigDecimal.valueOf(c.getNumericCellValue()).setScale(2, java.math.RoundingMode.HALF_UP);
        }
        String s = text(r, col);
        if (s == null) return null;
        try {
            return new BigDecimal(s.replaceAll("[$,\\s]", "").replace("USD", ""));
        } catch (NumberFormatException e) {
            throw new BusinessException("'" + s + "' is not an amount.");
        }
    }

    private LocalDate date(Row r, Integer col) {
        if (col == null) return null;
        Cell c = r.getCell(col);
        if (c == null) return null;
        if (c.getCellType() == CellType.NUMERIC) return c.getLocalDateTimeCellValue().toLocalDate();
        String s = text(r, col);
        if (s == null) return null;
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        throw new BusinessException("'" + s + "' is not a date (use dd/mm/yyyy).");
    }

    private LocalDate safeDate(Row r, Integer col) {
        try {
            return date(r, col);
        } catch (BusinessException e) {
            return null;
        }
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase();
    }
}
