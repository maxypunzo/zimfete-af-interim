package zw.co.zimfete.afs.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.*;

/**
 * One-off import of the previous AFM's Excel workbook ("Asset Financing sheet").
 *
 * <p>Rules, agreed with the acting AFM: values are taken as recorded and never corrected or guessed; anything
 * missing stays blank; where two sheets disagree nothing is filled in and the difference is listed "to confirm".
 * <ul>
 *   <li>The <b>client database</b> sheet (plus <b>Incomplete records</b>) is the master register: clients, accounts,
 *   one project per row, opening fees and amounts deposited. Amounts come in as balances brought forward (they are
 *   not cash received in this system, so they stay out of the cash reports).</li>
 *   <li><b>Deposits</b>, <b>Repayments</b> and <b>Project approval</b> add a project cost, recorded loan figures and
 *   committee approval only when the row matches a client by account number + name/phone, by exact name or by
 *   phone, and only when the sheets agree.</li>
 *   <li>The <b>daily inflow</b> sheets, the airtime line of the <b>August cashflow</b> and the <b>Aug outflow</b>
 *   payouts come in as old cash-book entries so that monthly reports for those months match her sheets. They keep
 *   the payer names as written and are not linked to clients.</li>
 *   <li>Decisions already made: Pefenia Kahuni's account is MRE2614ME (MRE2622ME was a duplicate entry);
 *   MRE2627ME belongs to Mercy Mukuzo. Anyone on "Incomplete records" whose number is already used in the client
 *   database gets a new number, as decided for Lancelot Chaitezvi.</li>
 * </ul>
 */
@Service
public class OldRegisterMigrationService {
    /** Old account number recorded twice for the same client → the number to keep. */
    static final Map<String, String> DUPLICATE_ACCOUNTS = Map.of("MRE2622ME", "MRE2614ME");

    /** District sections of the client database and the account-number prefixes → branch code. */
    private static final Map<String, String> DISTRICT_CODES = Map.of(
            "MUREHWA", "MRE", "MUREWA", "MRE", "MARONDERA", "MDA", "WEDZA", "WED", "HWEDZA", "WED",
            "MUTOKO", "MTK", "MUDZI", "MDZ", "GOROMONZI", "GMZ", "UMP", "UMP", "HARARE", "HRE");

    private static final String OLD_AFM = "Previous AFM (old register)";

    public record Issue(String sheet, String where, String who, String message) {
    }

    public record CreatedClient(String clientNo, String name, String accountNo, String branch, String note) {
    }

    public record Result(LocalDate asAt, int clients, int accounts, int projects, int loans, int cashbookReceipts,
                         int expenses, int payouts, BigDecimal feesBroughtForward, BigDecimal depositsBroughtForward,
                         BigDecimal repaymentsBroughtForward, BigDecimal oldTotalFees, BigDecimal oldTotalDeposits,
                         List<String> log, List<Issue> toConfirm, List<CreatedClient> created) {
    }

    private final BranchRepository branches;
    private final ClientRepository clients;
    private final AssetAccountRepository accounts;
    private final ProjectRepository projects;
    private final ReceiptRepository receipts;
    private final ExpenseRepository expenses;
    private final NumberService numbers;
    private final StatusService status;
    private final DataFormatter formatter = new DataFormatter();

    public OldRegisterMigrationService(BranchRepository branches, ClientRepository clients, AssetAccountRepository accounts,
                                       ProjectRepository projects, ReceiptRepository receipts, ExpenseRepository expenses,
                                       NumberService numbers, StatusService status) {
        this.branches = branches;
        this.clients = clients;
        this.accounts = accounts;
        this.projects = projects;
        this.receipts = receipts;
        this.expenses = expenses;
        this.numbers = numbers;
        this.status = status;
    }

    public boolean alreadyImported() {
        return receipts.existsBySource(Receipt.SOURCE_OPENING_BALANCE) || receipts.existsBySource(Receipt.SOURCE_OLD_CASHBOOK);
    }

    // ------------------------------------------------------------------------------------------ working state

    /** One row of the client database / incomplete records. */
    private record RegRow(String sheet, int row, LocalDate date, String name, String contact, String accountNo,
                          String purpose, String area, String active, BigDecimal fee, BigDecimal deposited, String district) {
        String ref() { return sheet + " row " + row; }
    }

    /** A client being built, with its account and projects. */
    private static final class Group {
        final List<RegRow> rows = new ArrayList<>();
        Client client;
        AssetAccount account;
        final List<Project> projects = new ArrayList<>();
        final Map<Project, RegRow> projectRows = new HashMap<>();
        final Map<Project, List<String>> costs = new HashMap<>();
        final Map<Project, List<String>> notes = new HashMap<>();
        RegRow first() { return rows.get(0); }
    }

    private final class Run {
        final LocalDate asAt;
        final List<String> log = new ArrayList<>();
        final List<Issue> issues = new ArrayList<>();
        final List<CreatedClient> created = new ArrayList<>();
        final List<Group> groups = new ArrayList<>();
        BigDecimal fees = BigDecimal.ZERO, deposits = BigDecimal.ZERO, repaid = BigDecimal.ZERO;
        BigDecimal oldTotalFees, oldTotalDeposits;
        BigDecimal columnFees = BigDecimal.ZERO, columnDeposits = BigDecimal.ZERO;
        int loans, cashbook, expenseRows, payouts;
        Branch hq;

        Run(LocalDate asAt) {
            this.asAt = asAt;
        }

        void issue(String sheet, String where, String who, String message) {
            issues.add(new Issue(sheet, where, who, message));
        }
    }

    // ------------------------------------------------------------------------------------------ entry point

    @Transactional
    public Result migrate(InputStream in, LocalDate asAt) throws IOException {
        if (alreadyImported()) throw new BusinessException("The old workbook has already been imported.");
        Run run = new Run(asAt != null ? asAt : LocalDate.now());
        run.hq = branches.findFirstByHeadOfficeTrue().orElseThrow();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet db = sheet(wb, "client database");
            if (db == null) throw new BusinessException("This is not the old asset finance workbook: no 'client database' sheet.");
            List<RegRow> master = readRegister(run, db, true);
            Sheet incomplete = sheet(wb, "incomplete records");
            List<RegRow> partial = incomplete == null ? List.of() : readRegister(run, incomplete, false);

            buildGroups(run, master, partial);
            createRecords(run);
            applyDepositsSheet(run, sheet(wb, "deposits"));
            applyRepaymentsSheet(run, sheet(wb, "repayments"));
            applyApprovalSheet(run, sheet(wb, "project approval"));
            finishProjects(run);

            for (Sheet s : wb) {
                if (s.getSheetName().toLowerCase().contains("daily inflow")) importCashbook(run, s);
            }
            importCashflowSheet(run, sheet(wb, "august cashflow"));
            importOutflow(run, sheet(wb, "aug outflow"));
            Sheet providers = sheet(wb, "service provider");
            if (providers != null) {
                run.log.add("Service providers sheet: not imported (there is no supplier register yet); suppliers can be typed on each project.");
            }
        }
        reconcileTotals(run);
        int projectCount = run.groups.stream().mapToInt(g -> g.projects.size()).sum();
        return new Result(run.asAt, run.groups.size(), (int) run.groups.stream().filter(g -> g.account != null).count(),
                projectCount, run.loans, run.cashbook, run.expenseRows, run.payouts, run.fees, run.deposits, run.repaid,
                run.oldTotalFees, run.oldTotalDeposits, run.log, run.issues, run.created);
    }

    /** The import result as a workbook: summary, everything to confirm, and the clients created. */
    public byte[] reportWorkbook(Result r) throws IOException {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            CellStyle wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);

            Sheet s = wb.createSheet("Summary");
            String[][] lines = {
                    {"Old register import", ""},
                    {"Balances brought forward as at", r.asAt().toString()},
                    {"Clients", String.valueOf(r.clients())},
                    {"Accounts", String.valueOf(r.accounts())},
                    {"Projects", String.valueOf(r.projects())},
                    {"Loans (as recorded)", String.valueOf(r.loans())},
                    {"Opening fees brought forward", money(r.feesBroughtForward())},
                    {"Deposits brought forward", money(r.depositsBroughtForward())},
                    {"Repayments brought forward", money(r.repaymentsBroughtForward())},
                    {"Old cash-book receipts", String.valueOf(r.cashbookReceipts())},
                    {"Old cash-book expenditure lines", String.valueOf(r.expenses())},
                    {"Old project payouts", String.valueOf(r.payouts())},
                    {"Items to confirm", String.valueOf(r.toConfirm().size())},
            };
            int i = 0;
            for (String[] l : lines) {
                Row row = s.createRow(i++);
                row.createCell(0).setCellValue(l[0]);
                row.createCell(1).setCellValue(l[1]);
            }
            s.getRow(0).getCell(0).setCellStyle(bold);
            i++;
            for (String l : r.log()) s.createRow(i++).createCell(0).setCellValue(l);
            s.setColumnWidth(0, 60 * 256);
            s.setColumnWidth(1, 20 * 256);

            Sheet c = wb.createSheet("To confirm");
            String[] head = {"#", "Sheet", "Where", "Client / item", "What to confirm", "Your decision"};
            Row h = c.createRow(0);
            for (int k = 0; k < head.length; k++) {
                h.createCell(k).setCellValue(head[k]);
                h.getCell(k).setCellStyle(bold);
            }
            i = 1;
            for (Issue is : r.toConfirm()) {
                Row row = c.createRow(i);
                row.createCell(0).setCellValue(i);
                row.createCell(1).setCellValue(is.sheet());
                row.createCell(2).setCellValue(is.where());
                row.createCell(3).setCellValue(is.who());
                Cell m = row.createCell(4);
                m.setCellValue(is.message());
                m.setCellStyle(wrap);
                i++;
            }
            int[] widths = {5, 20, 14, 30, 90, 40};
            for (int k = 0; k < widths.length; k++) c.setColumnWidth(k, widths[k] * 256);
            c.createFreezePane(0, 1);

            Sheet cl = wb.createSheet("Clients created");
            String[] ch = {"Client No", "Name (as recorded)", "Account No", "Branch / location", "Note"};
            h = cl.createRow(0);
            for (int k = 0; k < ch.length; k++) {
                h.createCell(k).setCellValue(ch[k]);
                h.getCell(k).setCellStyle(bold);
            }
            i = 1;
            for (CreatedClient cc : r.created()) {
                Row row = cl.createRow(i++);
                row.createCell(0).setCellValue(cc.clientNo());
                row.createCell(1).setCellValue(cc.name());
                if (cc.accountNo() != null) row.createCell(2).setCellValue(cc.accountNo());
                row.createCell(3).setCellValue(cc.branch());
                if (cc.note() != null) row.createCell(4).setCellValue(cc.note());
            }
            int[] cw = {14, 32, 14, 24, 60};
            for (int k = 0; k < cw.length; k++) cl.setColumnWidth(k, cw[k] * 256);
            cl.createFreezePane(0, 1);

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------------------------------ master register

    private List<RegRow> readRegister(Run run, Sheet s, boolean master) {
        Map<String, Integer> cols = header(s, 0);
        List<RegRow> out = new ArrayList<>();
        String district = null;
        for (Row r : s) {
            if (r.getRowNum() == 0) continue;
            String a = text(r, 0);
            String name = text(r, col(cols, "NAME"));
            if (a != null && a.equalsIgnoreCase("TOTALS")) {
                run.oldTotalFees = number(r, col(cols, "ACC OPENING FEES"));
                run.oldTotalDeposits = number(r, col(cols, "AMOUNT DEPOSITED"));
                continue;
            }
            if (name != null && name.toUpperCase().endsWith("DISTRICT") && text(r, col(cols, "ACCOUNT NUMBER")) == null) {
                district = name.trim().substring(0, name.trim().length() - "DISTRICT".length()).trim();
                continue;
            }
            if (name == null) continue;
            String acc = upper(text(r, col(cols, "ACCOUNT NUMBER")));
            String rowDistrict = master ? district : districtFromAccount(acc);
            if (master) {
                run.columnFees = run.columnFees.add(Optional.ofNullable(number(r, col(cols, "ACC OPENING FEES"))).orElse(BigDecimal.ZERO));
                run.columnDeposits = run.columnDeposits.add(Optional.ofNullable(number(r, col(cols, "AMOUNT DEPOSITED"))).orElse(BigDecimal.ZERO));
            }
            out.add(new RegRow(s.getSheetName(), r.getRowNum() + 1, date(r, col(cols, "DATE")), name.trim(),
                    phone(r, col(cols, "CONTACT")), acc, text(r, col(cols, "PURPOSE")), text(r, col(cols, "WARD OR AREA")),
                    text(r, col(cols, "ACTIVE")), number(r, col(cols, "ACC OPENING FEES")), number(r, col(cols, "AMOUNT DEPOSITED")),
                    rowDistrict));
        }
        run.log.add(s.getSheetName() + ": " + out.size() + " rows read.");
        return out;
    }

    private void buildGroups(Run run, List<RegRow> master, List<RegRow> partial) {
        Map<String, Group> byAccount = new LinkedHashMap<>();
        for (RegRow r : master) {
            String acc = r.accountNo();
            if (acc != null && DUPLICATE_ACCOUNTS.containsKey(acc)) {
                String keep = DUPLICATE_ACCOUNTS.get(acc);
                run.issue(r.sheet(), "row " + r.row(), r.name(), "Duplicate entry under " + acc + " (opening fee " + money(r.fee())
                        + ", deposited " + money(r.deposited()) + ") not imported: the account is " + keep + " as decided.");
                continue;
            }
            Group g = acc == null ? null : byAccount.get(acc);
            if (g != null) {
                RegRow first = g.first();
                boolean samePerson = Objects.equals(first.contact(), r.contact()) || normName(first.name()).equals(normName(r.name()));
                if (!samePerson) {
                    run.issue(r.sheet(), "row " + r.row(), r.name(), "Account " + acc + " is also recorded for " + first.name()
                            + " (" + first.ref() + "). Imported as a separate client with a new account number: confirm.");
                } else {
                    if (!first.name().equals(r.name())) {
                        run.issue(r.sheet(), "row " + r.row(), first.name(), "Recorded as \"" + r.name() + "\" on this row; same account "
                                + acc + " and phone, so imported as a further project for " + first.name() + ".");
                    }
                    g.rows.add(r);
                    continue;
                }
            }
            g = new Group();
            g.rows.add(r);
            run.groups.add(g);
            if (acc != null) byAccount.put(acc, g);
        }
        for (RegRow r : partial) {
            Group g = new Group();
            g.rows.add(r);
            run.groups.add(g);
            Group holder = r.accountNo() == null ? null : byAccount.get(r.accountNo());
            if (holder != null) {
                run.issue(r.sheet(), "row " + r.row(), r.name(), "Account number " + r.accountNo() + " is already "
                        + holder.first().name() + "'s in the client database: a new account number has been issued.");
            } else if (r.accountNo() != null) {
                byAccount.put(r.accountNo(), g);
            }
        }
    }

    private void createRecords(Run run) {
        Set<String> usedNumbers = new HashSet<>();
        for (Group g : run.groups) {
            RegRow f = g.first();
            Branch b = branchFor(run, f.district(), f);
            Client c = new Client();
            c.setClientNo(numbers.clientNo(b, clients::existsByClientNo));
            c.setFirstName(f.name());
            c.setPhone(f.contact());
            c.setWard(f.area());
            c.setDistrict(f.district() == null ? null : title(f.district()));
            c.setBranch(b);
            c.setDateRegistered(f.date());
            c.setCategory(MemberCategory.UNKNOWN);
            c.setNotes("Imported from the old register (" + f.ref() + ").");
            clients.save(c);
            g.client = c;

            String acc = f.accountNo();
            boolean reissue = acc != null && (usedNumbers.contains(acc) || accounts.existsByAccountNoIgnoreCase(acc));
            boolean blankRow = acc == null && f.purpose() == null && f.fee() == null && f.deposited() == null;
            AssetAccount a = new AssetAccount();
            a.setClient(c);
            a.setBranch(b);
            a.setOpenedDate(f.date());
            a.setOpenedBy(null);
            if (acc != null && !reissue) {
                a.setAccountNo(acc);
            } else if (acc != null) {
                LocalDate when = f.date() != null ? f.date() : run.asAt;
                a.setAccountNo(numbers.accountNo(b, when, n -> usedNumbers.contains(n) || accounts.existsByAccountNoIgnoreCase(n)));
                a.setNotes("Old register number " + acc + " was also used for another client; new number issued.");
            }
            if (blankRow && acc == null) {
                run.issue(f.sheet(), "row " + f.row(), f.name(), "No account number, purpose or amounts recorded: imported as a client only.");
            } else {
                if (a.getAccountNo() == null) {
                    run.issue(f.sheet(), "row " + f.row(), f.name(), "No account number recorded: account left without a number to confirm.");
                }
                accounts.save(a);
                g.account = a;
                if (a.getAccountNo() != null) usedNumbers.add(a.getAccountNo());
            }
            created(run, g, a.getNotes());

            long feeRows = g.rows.stream().filter(r -> r.fee() != null && r.fee().signum() > 0).count();
            if (feeRows > 1) {
                run.issue(f.sheet(), "row " + f.row(), f.name(), "An opening fee is recorded on " + feeRows + " rows of account "
                        + g.account.getNumberLabel() + " (" + g.rows.stream().map(r -> money(r.fee())).collect(Collectors.joining(" + "))
                        + "); all brought forward as recorded. Confirm whether the fee was paid more than once.");
            }
            for (RegRow r : g.rows) {
                if (g.account == null) break;
                if (r.fee() != null && r.fee().signum() > 0) {
                    broughtForward(run, g.account, null, b, ReceiptType.ACCOUNT_OPENING, r.fee(), "BF-" + code(r) + "-FEE",
                            "Opening fee brought forward from " + r.ref());
                    run.fees = run.fees.add(r.fee());
                }
                if (r == g.first()) checkActiveFlag(run, r);
                if (r.purpose() != null || (r.deposited() != null && r.deposited().signum() > 0)) {
                    Project p = new Project();
                    p.setAccount(g.account);
                    p.setAssetType(r.purpose() == null ? null : AssetType.parse(r.purpose()));
                    p.setAssetDescription(r.purpose());
                    p.setCreatedDate(r.date());
                    p.setInterestPercent(LoanTerms.DEFAULT_INTEREST_PERCENT);
                    projects.save(p);
                    g.projects.add(p);
                    g.projectRows.put(p, r);
                    g.costs.put(p, new ArrayList<>());
                    g.notes.put(p, new ArrayList<>());
                    if (r.deposited() != null && r.deposited().signum() > 0) {
                        broughtForward(run, g.account, p, b, ReceiptType.ASSET_DEPOSIT, r.deposited(), "BF-" + code(r) + "-DEP",
                                "Amount deposited brought forward from " + r.ref());
                        run.deposits = run.deposits.add(r.deposited());
                    }
                }
            }
        }
    }

    private void checkActiveFlag(Run run, RegRow r) {
        if (r.active() == null) return;
        boolean yes = r.active().trim().equalsIgnoreCase("YES");
        boolean paid = r.fee() != null && r.fee().compareTo(AssetAccount.OPENING_FEE) >= 0;
        if (yes != paid) {
            run.issue(r.sheet(), "row " + r.row(), r.name(), "ACTIVE is \"" + r.active().trim() + "\" but the opening fee recorded is "
                    + money(r.fee()) + ". The system shows the account as " + (paid ? "active" : "inactive") + " from the fee.");
        }
    }

    private Branch branchFor(Run run, String district, RegRow r) {
        String code = district == null ? null : DISTRICT_CODES.get(district.toUpperCase().replaceAll("[^A-Z]", ""));
        if (code == null) {
            run.issue(r.sheet(), "row " + r.row(), r.name(), "No district recorded: put under " + run.hq.getName() + " (HQ). Confirm the branch.");
            return run.hq;
        }
        Optional<Branch> b = branches.findByCode(code);
        if (b.isPresent()) return b.get();
        // a location with no branch (Harare): kept for the historical record only
        Branch created = branches.save(Branch.historical(code, title(district), title(district)));
        run.log.add("Created historical location " + created.getName() + " (no branch) for old records.");
        return created;
    }

    private String districtFromAccount(String acc) {
        if (acc == null || acc.length() < 3) return null;
        String prefix = acc.substring(0, 3);
        return DISTRICT_CODES.entrySet().stream().filter(e -> e.getValue().equals(prefix)).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private void created(Run run, Group g, String note) {
        run.created.add(new CreatedClient(g.client.getClientNo(), g.client.getFullName(),
                g.account == null ? null : g.account.getAccountNo(), g.client.getBranch().getName(), note));
    }

    // ------------------------------------------------------------------------------------------ secondary sheets

    /** A row on another sheet that refers to a client. */
    private record Ref(String sheet, int row, String name, String contact, String accountNo, String purpose) {
        String where() { return "row " + row; }
    }

    /** Finds the client the row is about: account number + name/phone, exact name, or phone. Null if unsure. */
    private Group match(Run run, Ref ref) {
        String acc = ref.accountNo() == null ? null : DUPLICATE_ACCOUNTS.getOrDefault(ref.accountNo(), ref.accountNo());
        String nm = normName(ref.name());
        if (acc != null) {
            for (Group g : run.groups) {
                if (g.account != null && acc.equals(g.account.getAccountNo())
                        && (nm.equals(normName(g.first().name())) || (ref.contact() != null && ref.contact().equals(g.first().contact())))) {
                    return g;
                }
            }
        }
        List<Group> byName = run.groups.stream().filter(g -> g.rows.stream().anyMatch(r -> normName(r.name()).equals(nm))).toList();
        if (byName.size() == 1) return byName.get(0);
        if (ref.contact() != null) {
            List<Group> byPhone = run.groups.stream().filter(g -> ref.contact().equals(g.first().contact())).toList();
            if (byPhone.size() == 1) return byPhone.get(0);
        }
        return null;
    }

    /** The project on the matched client that the row is about, or null if it cannot be told. */
    private Project projectFor(Group g, String purpose) {
        if (g.projects.size() == 1) return g.projects.get(0);
        if (purpose == null) return null;
        String want = purpose.trim().toUpperCase();
        List<Project> exact = g.projects.stream().filter(p -> p.getAssetDescription() != null
                && p.getAssetDescription().trim().equalsIgnoreCase(want)).toList();
        if (exact.size() == 1) return exact.get(0);
        AssetType t = AssetType.parse(purpose);
        List<Project> byType = g.projects.stream().filter(p -> p.getAssetType() == t).toList();
        return byType.size() == 1 ? byType.get(0) : null;
    }

    private String suggestion(Run run, String name) {
        String last = normName(name).replaceAll(".* ", "");
        List<String> hits = run.groups.stream().map(g -> g.first().name())
                .filter(n -> Arrays.asList(normName(n).split(" ")).contains(last)).distinct().toList();
        return hits.isEmpty() ? "" : " Possible match: " + String.join(", ", hits) + ".";
    }

    private void applyDepositsSheet(Run run, Sheet s) {
        if (s == null) return;
        Map<String, Integer> cols = header(s, 0);
        int used = 0;
        for (Row r : s) {
            if (r.getRowNum() == 0 || text(r, col(cols, "NAME")) == null) continue;
            Ref ref = new Ref(s.getSheetName(), r.getRowNum() + 1, text(r, col(cols, "NAME")), phone(r, col(cols, "CONTACT")),
                    upper(text(r, col(cols, "ACCOUNT NUMBER"))), text(r, col(cols, "PURPOSE")));
            Group g = match(run, ref);
            if (g == null) {
                run.issue(ref.sheet(), ref.where(), ref.name(), "Not matched to the client database; nothing taken from this row." + suggestion(run, ref.name()));
                continue;
            }
            Project p = projectFor(g, ref.purpose());
            if (p == null) {
                run.issue(ref.sheet(), ref.where(), ref.name(), "Cannot tell which of the client's projects \"" + ref.purpose() + "\" is; nothing taken.");
                continue;
            }
            used++;
            noteAccountNumber(run, ref, g);
            BigDecimal cost = number(r, col(cols, "TOTAL COST"));
            if (cost != null) g.costs.get(p).add(money(cost) + " (Deposits sheet)");
            compareDeposited(run, ref, g, p, number(r, col(cols, "AMOUNT DEPOSITED")));
            BigDecimal fee = number(r, col(cols, "ACC OPENING FEES"));
            BigDecimal dbFee = g.projectRows.get(p).fee();
            if (fee != null && dbFee != null && fee.compareTo(dbFee) != 0) {
                run.issue(ref.sheet(), ref.where(), ref.name(), "Opening fee " + money(fee) + " here vs " + money(dbFee) + " in the client database (kept).");
            }
        }
        run.log.add(s.getSheetName() + ": " + used + " rows matched.");
    }

    private void applyRepaymentsSheet(Run run, Sheet s) {
        if (s == null) return;
        Map<String, Integer> cols = header(s, 0);
        int used = 0;
        for (Row r : s) {
            if (r.getRowNum() == 0 || text(r, col(cols, "NAME")) == null) continue;
            Ref ref = new Ref(s.getSheetName(), r.getRowNum() + 1, text(r, col(cols, "NAME")), phone(r, col(cols, "CONTACT")),
                    upper(text(r, col(cols, "ACCOUNT NUMBER"))), text(r, col(cols, "PURPOSE")));
            BigDecimal cost = number(r, col(cols, "TOTAL PROJECT COST"));
            BigDecimal deposited = number(r, col(cols, "AMT DEPOSITED"));
            BigDecimal loan = number(r, col(cols, "TOTAL LOAN"));
            BigDecimal grand = number(r, col(cols, "GRAND TOTAL"));
            BigDecimal instalment = number(r, col(cols, "INSTALLMENTS"));
            BigDecimal paid = number(r, col(cols, "AMT PAID"));
            BigDecimal balance = number(r, col(cols, "BALANCE"));

            Group g = match(run, ref);
            if (g == null) {
                if (loan == null) {
                    run.issue(ref.sheet(), ref.where(), ref.name(), "Not matched to the client database; nothing taken." + suggestion(run, ref.name()));
                    continue;
                }
                g = clientFromRepayments(run, ref);
            }
            Project p = projectFor(g, ref.purpose());
            if (p == null) {
                run.issue(ref.sheet(), ref.where(), ref.name(), "Cannot tell which of the client's projects \"" + ref.purpose() + "\" is; nothing taken.");
                continue;
            }
            used++;
            noteAccountNumber(run, ref, g);
            if (cost != null) g.costs.get(p).add(money(cost) + " (Repayments sheet)");
            compareDeposited(run, ref, g, p, deposited);
            if (loan == null) {
                if (grand != null || paid != null) run.issue(ref.sheet(), ref.where(), ref.name(), "Repayment figures without a TOTAL LOAN: not imported.");
                continue;
            }
            // loan exactly as recorded
            p.setLoanPrincipal(loan);
            if (grand != null) {
                p.setLoanInterest(grand.subtract(loan));
            } else {
                run.issue(ref.sheet(), ref.where(), ref.name(), "No GRAND TOTAL recorded: interest left at the 30% policy figure. Confirm.");
            }
            p.setInterestPercent(grand != null ? null : LoanTerms.DEFAULT_INTEREST_PERCENT);
            p.setInstalmentAmount(instalment);
            p.setStatus(ProjectStatus.IN_PROGRESS);
            p.setRateDecision(grand != null ? "As recorded in the old Repayments sheet" : null);
            projects.save(p);
            run.loans++;
            g.notes.get(p).add("Loan as recorded in the old Repayments sheet (" + ref.where() + "): total loan " + money(loan)
                    + ", grand total " + money(grand) + ", instalment " + money(instalment) + ". Disbursement date not recorded.");
            if (paid != null && paid.signum() > 0) {
                broughtForward(run, g.account, p, g.client.getBranch(), ReceiptType.LOAN_REPAYMENT, paid,
                        "BF-RP" + ref.row() + "-REP", "Amount repaid brought forward from Repayments " + ref.where());
                run.repaid = run.repaid.add(paid);
            }
            if (grand != null && balance != null) {
                BigDecimal expected = grand.subtract(paid == null ? BigDecimal.ZERO : paid);
                if (expected.compareTo(balance) != 0) {
                    run.issue(ref.sheet(), ref.where(), ref.name(), "BALANCE recorded " + money(balance) + " but grand total − paid = " + money(expected) + ".");
                }
            }
        }
        run.log.add(s.getSheetName() + ": " + used + " rows matched, " + run.loans + " loans imported as recorded.");
    }

    /** Someone with a recorded loan who is missing from the client database (e.g. Ashuate Zadzi). */
    private Group clientFromRepayments(Run run, Ref ref) {
        RegRow row = new RegRow(ref.sheet(), ref.row(), null, ref.name().trim(), ref.contact(), null, ref.purpose(), null, null, null, null, null);
        Group g = new Group();
        g.rows.add(row);
        run.groups.add(g);
        Client c = new Client();
        c.setClientNo(numbers.clientNo(run.hq, clients::existsByClientNo));
        c.setFirstName(row.name());
        c.setPhone(row.contact());
        c.setBranch(run.hq);
        c.setCategory(MemberCategory.UNKNOWN);
        c.setNotes("Imported from the old Repayments sheet (" + ref.where() + "); not in the client database.");
        clients.save(c);
        g.client = c;
        AssetAccount a = new AssetAccount();
        a.setClient(c);
        a.setBranch(run.hq);
        accounts.save(a);
        g.account = a;
        Project p = new Project();
        p.setAccount(a);
        p.setAssetType(ref.purpose() == null ? null : AssetType.parse(ref.purpose()));
        p.setAssetDescription(ref.purpose());
        p.setInterestPercent(LoanTerms.DEFAULT_INTEREST_PERCENT);
        projects.save(p);
        g.projects.add(p);
        g.projectRows.put(p, row);
        g.costs.put(p, new ArrayList<>());
        g.notes.put(p, new ArrayList<>());
        created(run, g, "Not in the client database");
        run.issue(ref.sheet(), ref.where(), ref.name(), "Has a loan but is not in the client database: created under "
                + run.hq.getName() + " (HQ) with no account number and no deposit history. Confirm branch, account number and deposits.");
        return g;
    }

    private void applyApprovalSheet(Run run, Sheet s) {
        if (s == null) return;
        int headerRow = -1;
        for (Row r : s) {
            for (Cell c : r) {
                if ("NAME".equalsIgnoreCase(formatter.formatCellValue(c).trim())) headerRow = r.getRowNum();
            }
            if (headerRow >= 0) break;
        }
        if (headerRow < 0) return;
        Map<String, Integer> cols = header(s, headerRow);
        Map<Project, List<Integer>> rowsPerProject = new HashMap<>();
        Map<Integer, Object[]> pending = new LinkedHashMap<>();
        for (Row r : s) {
            if (r.getRowNum() <= headerRow) continue;
            String name = text(r, col(cols, "NAME"));
            if (name == null) name = text(r, 0); // one row has the name one column to the left
            if (name == null) continue;
            Ref ref = new Ref(s.getSheetName(), r.getRowNum() + 1, name, null, null, text(r, col(cols, "PROJECT")));
            Group g = match(run, ref);
            if (g == null) {
                run.issue(ref.sheet(), ref.where(), name, "Not matched exactly to the client database; nothing taken." + suggestion(run, name));
                continue;
            }
            Project p = projectFor(g, ref.purpose());
            if (p == null) {
                run.issue(ref.sheet(), ref.where(), name, "Project \"" + ref.purpose() + "\" does not match one of " + g.first().name()
                        + "'s projects in the client database; nothing taken.");
                continue;
            }
            rowsPerProject.computeIfAbsent(p, k -> new ArrayList<>()).add(r.getRowNum());
            pending.put(r.getRowNum(), new Object[] {ref, g, p, number(r, col(cols, "AMOUNT DEPOSITED")),
                    number(r, col(cols, "LOAN REQUIREMENT")), number(r, col(cols, "PROJECT COST")), text(r, col(cols, "PROPOSED DATE"))});
        }
        int used = 0;
        for (Object[] v : pending.values()) {
            Ref ref = (Ref) v[0];
            Group g = (Group) v[1];
            Project p = (Project) v[2];
            if (rowsPerProject.get(p).size() > 1) {
                run.issue(ref.sheet(), ref.where(), ref.name(), "Several approval rows point to the same project (\"" + p.getAssetDescription()
                        + "\"); nothing taken from them. Confirm which projects these are.");
                continue;
            }
            used++;
            BigDecimal deposited = (BigDecimal) v[3], loanReq = (BigDecimal) v[4], cost = (BigDecimal) v[5];
            String proposed = (String) v[6];
            if (cost != null) g.costs.get(p).add(money(cost) + " (Project approval sheet)");
            compareDeposited(run, ref, g, p, deposited);
            if (loanReq != null) g.notes.get(p).add("Loan requirement on the approval sheet: " + money(loanReq) + ".");
            if (proposed != null) {
                boolean done = proposed.toLowerCase().contains("done");
                g.notes.get(p).add("Approval sheet, proposed date: \"" + proposed.trim() + "\".");
                if (done && p.getStatus() != ProjectStatus.IN_PROGRESS) {
                    p.setStatus(ProjectStatus.APPROVED);
                    p.setApprovalNote("Marked \"done\" on the old Project approval sheet (date not recorded)");
                }
            }
        }
        run.log.add(s.getSheetName() + ": " + used + " rows applied.");
    }

    private void compareDeposited(Run run, Ref ref, Group g, Project p, BigDecimal value) {
        BigDecimal db = g.projectRows.get(p).deposited();
        if (value == null || db == null) return;
        if (value.compareTo(db) != 0) {
            run.issue(ref.sheet(), ref.where(), ref.name(), "Amount deposited " + money(value) + " here vs " + money(db)
                    + " in the client database. The client database figure is used; confirm the correct balance.");
        }
    }

    private void noteAccountNumber(Run run, Ref ref, Group g) {
        if (ref.accountNo() == null || g.account == null || g.account.getAccountNo() == null) return;
        String acc = DUPLICATE_ACCOUNTS.getOrDefault(ref.accountNo(), ref.accountNo());
        if (!acc.equals(g.account.getAccountNo())) {
            run.issue(ref.sheet(), ref.where(), ref.name(), "Account number written as " + ref.accountNo() + " here; client database has "
                    + g.account.getAccountNo() + " (kept).");
        }
    }

    /** Sets the project cost only when every sheet that gives one agrees; records notes. */
    private void finishProjects(Run run) {
        for (Group g : run.groups) {
            for (Project p : g.projects) {
                List<String> costs = g.costs.getOrDefault(p, List.of());
                Set<String> distinct = costs.stream().map(c -> c.replaceAll(" \\(.*", "")).collect(Collectors.toCollection(TreeSet::new));
                if (distinct.size() == 1) {
                    p.setQuotationCost(new BigDecimal(distinct.iterator().next().replace("$", "")));
                } else if (distinct.size() > 1) {
                    run.issue("Several sheets", "", g.first().name(), "Project \"" + p.getAssetDescription() + "\": costs differ — "
                            + String.join("; ", costs) + ". Cost left blank.");
                    g.notes.get(p).add("Costs recorded differ: " + String.join("; ", costs) + ".");
                }
                List<String> notes = g.notes.getOrDefault(p, List.of());
                String base = "Imported from the old register (" + g.projectRows.get(p).ref() + ").";
                p.setNotes(notes.isEmpty() ? base : base + "\n" + String.join("\n", notes));
                projects.save(p);
                status.recalcProject(p);
            }
            if (g.account != null) status.recalcAccount(g.account);
            status.recalcClient(g.client);
        }
    }

    // ------------------------------------------------------------------------------------------ old cash book

    private void importCashbook(Run run, Sheet s) {
        Map<String, Integer> cols = header(s, 0);
        LocalDate current = null;
        int n = 0;
        Map<LocalDate, BigDecimal> perDay = new TreeMap<>();
        Map<String, Integer> numbersSeen = new HashMap<>();
        for (Row r : s) {
            if (r.getRowNum() == 0) continue;
            LocalDate d = date(r, col(cols, "DATE"));
            String first = text(r, 0);
            if (first != null && first.equalsIgnoreCase("total")) continue;
            if (d != null) current = d;
            String activity = text(r, col(cols, "ACTIVITY"));
            BigDecimal amount = number(r, col(cols, "AMOUNT"));
            if (activity == null && amount == null) continue;
            String receiptNo = text(r, col(cols, "RECEIPT NUMBER"));
            String payer = text(r, col(cols, "CLIENT"));
            String where = "row " + (r.getRowNum() + 1);
            if (current == null || amount == null) {
                run.issue(s.getSheetName(), where, payer, "No date or amount: line not imported.");
                continue;
            }
            ReceiptType type = cashbookType(activity);
            if (type == null) {
                type = ReceiptType.OTHER_INCOME;
                run.issue(s.getSheetName(), where, payer, "Activity \"" + activity + "\" not recognised: imported as other income.");
            }
            if (receiptNo != null && numbersSeen.merge(receiptNo, 1, Integer::sum) > 1) {
                run.issue(s.getSheetName(), where, payer, "Receipt number " + receiptNo + " is used more than once on this sheet (both lines imported as recorded).");
            }
            Receipt rc = new Receipt();
            rc.setReceiptNo(receiptNo == null ? "OLD-" + s.getSheetName().replaceAll("\\s+", "") + "-" + (r.getRowNum() + 1) : receiptNo);
            rc.setReceiptDate(current);
            rc.setBranch(run.hq);
            rc.setType(type);
            rc.setAmount(amount);
            rc.setDescription(payer);
            rc.setCapturedBy(OLD_AFM);
            rc.setSource(Receipt.SOURCE_OLD_CASHBOOK);
            receipts.save(rc);
            perDay.merge(current, amount, BigDecimal::add);
            n++;
        }
        run.cashbook += n;
        cashbookTotals.put(s.getSheetName(), perDay);
        run.log.add(s.getSheetName() + ": " + n + " receipts imported to the old cash book (" + run.hq.getName()
                + "), payer names as written, not linked to clients.");
    }

    private final Map<String, Map<LocalDate, BigDecimal>> cashbookTotals = new HashMap<>();

    private static ReceiptType cashbookType(String activity) {
        if (activity == null) return null;
        String a = activity.toLowerCase();
        if (a.contains("opening")) return ReceiptType.ACCOUNT_OPENING;
        if (a.contains("deposit")) return ReceiptType.ASSET_DEPOSIT;
        if (a.contains("joining")) return ReceiptType.JOINING_FEE;
        if (a.contains("repayment")) return ReceiptType.LOAN_REPAYMENT;
        if (a.contains("sub")) return ReceiptType.SUBSCRIPTION;
        return null;
    }

    /** The August cashflow summary: imports its airtime line, and checks its inflow totals against the daily sheet. */
    private void importCashflowSheet(Run run, Sheet s) {
        if (s == null) return;
        Row dates = null;
        for (Row r : s) {
            int dateCells = 0;
            for (Cell c : r) if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) dateCells++;
            if (dateCells > 5) {
                dates = r;
                break;
            }
        }
        if (dates == null) return;
        Map<Integer, LocalDate> colDate = new TreeMap<>();
        for (Cell c : dates) {
            if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) colDate.put(c.getColumnIndex(), c.getLocalDateTimeCellValue().toLocalDate());
        }
        Map<LocalDate, BigDecimal> daily = cashbookTotals.values().stream().flatMap(m -> m.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, BigDecimal::add));
        for (Row r : s) {
            String label = text(r, 0);
            if (label == null) continue;
            String l = label.toLowerCase();
            if (l.startsWith("airtime") || l.startsWith("workshop")) {
                for (Map.Entry<Integer, LocalDate> e : colDate.entrySet()) {
                    BigDecimal v = number(r, e.getKey());
                    if (v == null || v.signum() == 0) continue;
                    Expense x = new Expense();
                    x.setBranch(run.hq);
                    x.setExpenseDate(e.getValue());
                    x.setCategory(l.startsWith("airtime") ? "Airtime & travel" : "Workshops");
                    x.setDescription("From the old " + s.getSheetName() + " sheet");
                    x.setAmount(v);
                    x.setCapturedBy(OLD_AFM);
                    x.setSource(Receipt.SOURCE_OLD_CASHBOOK);
                    expenses.save(x);
                    run.expenseRows++;
                }
            }
            if (l.startsWith("total inflow")) {
                for (Map.Entry<Integer, LocalDate> e : colDate.entrySet()) {
                    BigDecimal sheet = Optional.ofNullable(number(r, e.getKey())).orElse(BigDecimal.ZERO);
                    BigDecimal book = daily.getOrDefault(e.getValue(), BigDecimal.ZERO);
                    if (sheet.compareTo(book) != 0) {
                        run.issue(s.getSheetName(), "column " + e.getKey(), e.getValue().toString(), "Total inflow " + money(sheet)
                                + " on the cashflow sheet vs " + money(book) + " in the daily inflow sheet (daily sheet imported).");
                    }
                }
            }
        }
        run.log.add(s.getSheetName() + ": " + run.expenseRows + " expenditure lines imported (airtime/workshops). Inflow taken from the daily sheets; "
                + "\"Loan (projects)\" taken from the Aug outflow sheet.");
    }

    private void importOutflow(Run run, Sheet s) {
        if (s == null) return;
        Map<String, Integer> cols = header(s, 0);
        for (Row r : s) {
            if (r.getRowNum() == 0) continue;
            String name = text(r, col(cols, "NAME"));
            if (name == null || name.equalsIgnoreCase("total")) continue;
            BigDecimal amount = number(r, col(cols, "TOTAL AMOUNT"));
            LocalDate d = date(r, col(cols, "DATE"));
            String project = text(r, col(cols, "PROJECT"));
            if (amount == null || d == null) {
                run.issue(s.getSheetName(), "row " + (r.getRowNum() + 1), name, "No amount or date: not imported.");
                continue;
            }
            Expense x = new Expense();
            x.setBranch(run.hq);
            x.setExpenseDate(d);
            x.setCategory("Loan (projects)");
            x.setPayee(name);
            x.setDescription(project);
            x.setAmount(amount);
            x.setProjectDisbursement(true);
            x.setCapturedBy(OLD_AFM);
            x.setSource(Receipt.SOURCE_OLD_CASHBOOK);
            expenses.save(x);
            run.payouts++;
            run.issue(s.getSheetName(), "row " + (r.getRowNum() + 1), name, "Payout " + money(amount) + " on " + d + " for \"" + project
                    + "\" imported to the cash book only; it is not linked to a project. Confirm which project it was and its start date.");
        }
        run.log.add(s.getSheetName() + ": " + run.payouts + " project payouts imported as outflow.");
    }

    private void reconcileTotals(Run run) {
        run.log.add("Opening fees: the client database column adds up to " + money(run.columnFees)
                + " (its TOTALS row shows " + money(run.oldTotalFees) + ", a saved formula result that is out of date). Brought forward: "
                + money(run.fees) + " = column, less the duplicate MRE2622ME row, plus Incomplete records.");
        run.log.add("Amounts deposited: the client database column adds up to " + money(run.columnDeposits)
                + " (TOTALS row shows " + money(run.oldTotalDeposits) + "). Brought forward: " + money(run.deposits)
                + " = column, less the duplicate MRE2622ME row.");
        if (run.oldTotalFees != null && run.oldTotalFees.compareTo(run.columnFees) != 0
                || run.oldTotalDeposits != null && run.oldTotalDeposits.compareTo(run.columnDeposits) != 0) {
            run.issue("client database", "TOTALS row", "", "The TOTALS row (" + money(run.oldTotalFees) + " fees, " + money(run.oldTotalDeposits)
                    + " deposits) does not equal its columns (" + money(run.columnFees) + ", " + money(run.columnDeposits)
                    + "): the workbook's saved totals were not recalculated. Column values were imported.");
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private void broughtForward(Run run, AssetAccount a, Project p, Branch b, ReceiptType type, BigDecimal amount, String no, String note) {
        Receipt r = new Receipt();
        r.setReceiptNo(no);
        r.setReceiptDate(run.asAt);
        r.setBranch(b);
        r.setClient(a.getClient());
        r.setAccount(a);
        r.setProject(p);
        r.setType(type);
        r.setAmount(amount);
        r.setDescription(note);
        r.setCapturedBy(OLD_AFM);
        r.setSource(Receipt.SOURCE_OPENING_BALANCE);
        receipts.save(r);
    }

    private static String code(RegRow r) {
        return (r.sheet().toLowerCase().startsWith("incomplete") ? "IR" : "CD") + r.row();
    }

    private Sheet sheet(Workbook wb, String name) {
        for (Sheet s : wb) if (s.getSheetName().trim().equalsIgnoreCase(name)) return s;
        return null;
    }

    private Map<String, Integer> header(Sheet s, int rowIndex) {
        Map<String, Integer> m = new LinkedHashMap<>();
        Row h = s.getRow(rowIndex);
        if (h == null) return m;
        for (Cell c : h) {
            String t = formatter.formatCellValue(c).trim().toUpperCase().replaceAll("\\s+", " ");
            if (!t.isEmpty()) m.putIfAbsent(t, c.getColumnIndex());
        }
        return m;
    }

    /** Column whose header is, or starts with, the given text. */
    private static Integer col(Map<String, Integer> cols, String name) {
        if (cols.containsKey(name)) return cols.get(name);
        return cols.entrySet().stream().filter(e -> e.getKey().startsWith(name)).map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private String text(Row r, Integer col) {
        if (col == null) return null;
        Cell c = r.getCell(col);
        if (c == null) return null;
        String s = formatter.formatCellValue(c).trim();
        return s.isEmpty() ? null : s;
    }

    private BigDecimal number(Row r, Integer col) {
        if (col == null) return null;
        Cell c = r.getCell(col);
        if (c == null) return null;
        if (c.getCellType() == CellType.NUMERIC || (c.getCellType() == CellType.FORMULA && c.getCachedFormulaResultType() == CellType.NUMERIC)) {
            return BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros();
        }
        String s = text(r, col);
        if (s == null) return null;
        try {
            return new BigDecimal(s.replaceAll("[$,\\s]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate date(Row r, Integer col) {
        if (col == null) return null;
        Cell c = r.getCell(col);
        if (c != null && c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
            return c.getLocalDateTimeCellValue().toLocalDate();
        }
        return null;
    }

    /** Phone as recorded (Excel keeps it as a number, so a leading 0 is not shown and is not added back). */
    private String phone(Row r, Integer col) {
        if (col == null) return null;
        Cell c = r.getCell(col);
        if (c == null) return null;
        if (c.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(c.getNumericCellValue()).toBigInteger().toString();
        return text(r, col);
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    static String normName(String s) {
        if (s == null) return "";
        String n = s.trim().toUpperCase().replaceAll("\\s+", " ");
        return n.replaceAll("^(MR|MRS|MS|MISS|DR)\\.? ", "");
    }

    private static String title(String s) {
        if (s == null || s.isBlank()) return s;
        String t = s.trim().toLowerCase();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static String money(BigDecimal b) {
        return b == null ? "(blank)" : "$" + b.stripTrailingZeros().toPlainString();
    }
}
