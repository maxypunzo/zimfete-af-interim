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
    private final ClientRepository clients;
    private final AssetAccountRepository accounts;
    private final ProjectRepository projects;
    private final ReceiptRepository receipts;
    private final ExpenseRepository expenses;
    private final ClientService clientService;
    private final AccountService accountService;
    private final ProjectService projectService;
    private final ReceiptService receiptService;
    private final TransactionTemplate tx;
    private final DataFormatter formatter = new DataFormatter();

    public ExcelImportService(BranchRepository branches, ClientRepository clients, AssetAccountRepository accounts,
                              ProjectRepository projects, ReceiptRepository receipts, ExpenseRepository expenses,
                              ClientService clientService, AccountService accountService, ProjectService projectService,
                              ReceiptService receiptService, PlatformTransactionManager tm) {
        this.branches = branches;
        this.clients = clients;
        this.accounts = accounts;
        this.projects = projects;
        this.receipts = receipts;
        this.expenses = expenses;
        this.clientService = clientService;
        this.accountService = accountService;
        this.projectService = projectService;
        this.receiptService = receiptService;
        this.tx = new TransactionTemplate(tm);
    }

    public ImportResult importReturn(InputStream in, Long branchId) throws IOException {
        Branch branch = branches.findById(branchId).orElseThrow(() -> new BusinessException("Choose the branch the return is from."));
        List<RowResult> results = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet rec = wb.getSheet("Receipts");
            Sheet exp = wb.getSheet("Expenditure");
            Sheet lists = wb.getSheet(ExcelExportService.BRANCH_CELL_SHEET);
            if (lists != null && lists.getRow(1) != null) {
                String code = text(lists.getRow(1), ExcelExportService.BRANCH_CELL_COL);
                if (code != null && !code.equalsIgnoreCase(branch.getCode())) {
                    throw new BusinessException("This return is " + code + "'s file, but you chose " + branch.getName()
                            + ". Choose the right branch and upload again.");
                }
            }
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
        if (amount == null || amount.signum() <= 0) throw new BusinessException("Amount is missing.");
        String clerk = text(row, c.get("clerk"));
        String method = Optional.ofNullable(text(row, c.get("payment method"))).orElse("Cash");
        String categoryText = text(row, c.get("category"));
        MemberCategory category = MemberCategory.parse(categoryText);
        if (categoryText != null && category == null) throw new BusinessException("Category '" + categoryText + "' is not recognised.");

        // --- find or register the client
        String nid = ClientService.normaliseId(text(row, c.get("national id")));
        String accountNo = upper(text(row, c.get("account no")));
        AssetAccount account = accountNo == null ? null : accounts.findByAccountNoIgnoreCase(accountNo).orElse(null);
        Client client = nid == null ? null : clients.findByNationalIdIgnoreCase(nid).orElse(null);
        if (client == null && account != null) client = account.getClient();
        String first = text(row, c.get("first name"));
        String surname = text(row, c.get("surname"));
        if (client == null && first != null) {
            List<Client> byName = clients.findByBranchAndFullName(branch.getId(), (first + " " + Optional.ofNullable(surname).orElse("")).trim());
            if (byName.size() == 1) client = byName.get(0);
            if (byName.size() > 1) throw new BusinessException(byName.size() + " clients are called " + first + " " + surname + ": fill in the National ID or Account No.");
        }
        String note = "";
        if (client == null) {
            if (first == null) throw new BusinessException("New client: First Name is needed (and National ID if known).");
            RegistrationRequest reg = new RegistrationRequest();
            reg.setBranchId(branch.getId());
            reg.setFirstName(first);
            reg.setSurname(surname);
            reg.setNationalId(nid);
            reg.setPhone(text(row, c.get("phone")));
            reg.setGender(text(row, c.get("gender")));
            reg.setVillage(text(row, c.get("village")));
            reg.setWard(text(row, c.get("ward")));
            reg.setDistrict(text(row, c.get("district / location")));
            reg.setCategory(category != null ? category : MemberCategory.NOT_VETERAN);
            reg.setDateRegistered(date);
            reg.setCapturedBy(clerk);
            client = clientService.register(reg, "IMPORT");
            note = "New client " + client.getClientNo() + ". ";
        }
        if (account != null && !account.getClient().getId().equals(client.getId())) {
            throw new BusinessException("Account " + account.getAccountNo() + " belongs to " + account.getClient().getFullName() + ".");
        }

        // --- joining fee / subscription: SACCO members only; a first joining fee enrols the client
        if (type.isMembershipFee() && !client.isSaccoMember()) {
            MemberCategory cat = category != null ? category : client.getCategory();
            if (type != ReceiptType.JOINING_FEE || cat == null || !cat.isVeteranCommunity()) {
                throw new BusinessException(client.getFullName() + " is not a SACCO member. Membership is for the veteran community: "
                        + "put their Category on the joining fee row.");
            }
            MembershipRequest m = new MembershipRequest();
            m.setCategory(cat);
            m.setMemberSince(date);
            m.setPayJoiningFee(false);
            m.setSubsMonths(0);
            clientService.enrol(client.getId(), m, "IMPORT");
            note += "Enrolled as SACCO member (" + cat.getLabel() + "). ";
        }

        ReceiptRequest req = new ReceiptRequest();
        req.setBranchId(branch.getId());
        req.setReceiptDate(date);
        req.setType(type);
        req.setClientId(client.getId());
        req.setAmount(amount);
        req.setReceiptNo(receiptNo);
        req.setPaymentMethod(method);
        req.setCapturedBy(clerk);
        req.setDescription(text(row, c.get("notes")));
        req.setSource("IMPORT");
        BigDecimal months = amount(row, c.get("months (subs)"));
        if (months != null) req.setMonths(months.intValue());

        ProjectRequest asset = assetDetails(row, c, date, clerk);

        if (type == ReceiptType.ACCOUNT_OPENING) {
            if (account == null) {
                AccountRequest ar = new AccountRequest();
                ar.setAccountNo(accountNo);
                ar.setOpenedDate(date);
                ar.setOpenedBy(clerk);
                ar.setOpeningFeeAmount(amount);
                ar.setOpeningReceiptNo(receiptNo);
                ar.setPaymentMethod(method);
                AssetAccount opened = accountService.open(client.getId(), ar, asset.isEmpty() ? null : asset, "IMPORT");
                return new RowResult("Receipts", n, Outcome.POSTED, note + "Opened account " + opened.getAccountNo() + " for "
                        + client.getFullName() + " ($" + amount + " of $" + AssetAccount.OPENING_FEE + " opening fee).");
            }
            req.setAccountId(account.getId()); // a further instalment of the opening fee
        }

        if (type.needsProject()) {
            if (account == null) account = onlyAccount(client, accountNo);
            req.setAccountId(account.getId());
            Project p = pickProject(account, type, asset);
            if (p == null && type == ReceiptType.ASSET_DEPOSIT && !asset.isEmpty()) {
                p = projectService.create(account.getId(), asset, "IMPORT");
                note += "New project " + p.getAssetLabel() + ". ";
            }
            if (p != null) {
                fillMissingAssetDetails(p, asset);
                req.setProjectId(p.getId());
            }
        }
        Receipt saved = receiptService.record(req);
        return new RowResult("Receipts", n, Outcome.POSTED, note + saved.getType().getLabel() + " $" + saved.getAmount()
                + " for " + client.getFullName() + (saved.getProject() != null ? " on " + saved.getProject().getLabel()
                : saved.getAccount() != null ? " on " + saved.getAccount().getAccountNo() : "") + ".");
    }

    private ProjectRequest assetDetails(Row row, Map<String, Integer> c, LocalDate date, String clerk) {
        ProjectRequest p = new ProjectRequest();
        p.setAssetType(AssetType.parse(text(row, c.get("asset type"))));
        p.setAssetDescription(text(row, c.get("asset description")));
        p.setQuotationCost(amount(row, c.get("quotation cost")));
        p.setTargetDate(safeDate(row, c.get("target date")));
        p.setCreatedDate(date);
        p.setCapturedBy(clerk);
        return p;
    }

    private AssetAccount onlyAccount(Client client, String accountNo) {
        if (accountNo != null) throw new BusinessException("Account " + accountNo + " not found.");
        List<AssetAccount> open = accounts.findByClientIdOrderByOpenedDateDesc(client.getId()).stream()
                .filter(a -> !a.isClosed()).toList();
        if (open.size() == 1) return open.get(0);
        throw new BusinessException(open.isEmpty() ? client.getFullName() + " has no asset finance account yet."
                : client.getFullName() + " has " + open.size() + " accounts: fill in Account No.");
    }

    /** The project a deposit/repayment is for: the only eligible one, or the one matching the asset type given. */
    private Project pickProject(AssetAccount account, ReceiptType type, ProjectRequest asset) {
        List<Project> eligible = projects.findByAccountIdOrderByIdAsc(account.getId()).stream()
                .filter(p -> type == ReceiptType.LOAN_REPAYMENT ? p.isLoanStarted() : p.getStatus().isPreStart()).toList();
        if (asset.getAssetType() != null) {
            List<Project> match = eligible.stream().filter(p -> p.getAssetType() == asset.getAssetType()).toList();
            if (match.size() == 1) return match.get(0);
            if (match.isEmpty() && type == ReceiptType.ASSET_DEPOSIT) return null; // a new project on this account
            if (match.size() > 1) throw new BusinessException("Account " + account.getAccountNo() + " has " + match.size() + " "
                    + asset.getAssetType().getLabel() + " projects: capture this one at HQ.");
        }
        if (eligible.size() == 1) return eligible.get(0);
        if (eligible.isEmpty()) {
            if (type == ReceiptType.ASSET_DEPOSIT && !asset.isEmpty()) return null;
            throw new BusinessException("Account " + account.getAccountNo() + (type == ReceiptType.LOAN_REPAYMENT
                    ? " has no running loan." : " has no project yet: fill Asset Type / Description / Quotation on this row."));
        }
        throw new BusinessException("Account " + account.getAccountNo() + " has " + eligible.size() + " projects: fill Asset Type to say which.");
    }

    private void fillMissingAssetDetails(Project p, ProjectRequest asset) {
        if (p.isLoanStarted()) return;
        boolean changed = false;
        if (p.getAssetType() == null && asset.getAssetType() != null) {
            p.setAssetType(asset.getAssetType());
            changed = true;
        }
        if (p.getAssetDescription() == null && asset.getAssetDescription() != null) {
            p.setAssetDescription(asset.getAssetDescription());
            changed = true;
        }
        if (p.getQuotationCost() == null && asset.getQuotationCost() != null) {
            p.setQuotationCost(asset.getQuotationCost());
            changed = true;
        }
        if (p.getTargetDate() == null && asset.getTargetDate() != null) {
            p.setTargetDate(asset.getTargetDate());
            changed = true;
        }
        if (changed) projects.save(p);
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
