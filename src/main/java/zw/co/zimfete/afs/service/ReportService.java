package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.*;

/** Builds the daily cash report, the monthly income & expenditure and the AFM dashboard figures. */
@Service
@Transactional(readOnly = true)
public class ReportService {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE dd MMM yyyy");

    private final ReceiptRepository receipts;
    private final ExpenseRepository expenses;
    private final ClientRepository clients;
    private final AssetAccountRepository accounts;
    private final ProjectRepository projects;
    private final BranchRepository branches;

    public ReportService(ReceiptRepository receipts, ExpenseRepository expenses, ClientRepository clients,
                         AssetAccountRepository accounts, ProjectRepository projects, BranchRepository branches) {
        this.receipts = receipts;
        this.expenses = expenses;
        this.clients = clients;
        this.accounts = accounts;
        this.projects = projects;
        this.branches = branches;
    }

    // ---------------------------------------------------------------- cash report (daily or any period)

    /**
     * Inflow (receipts) and outflow (operating expenditure plus funds disbursed to projects) for a period.
     * Income is ZimFete's own (fees); deposits and repayments are client funds and shown separately.
     */
    public record CashReport(LocalDate from, LocalDate to, Branch branch,
                             List<Receipt> receipts, List<Expense> expenses, List<Project> disbursements,
                             List<Expense> oldDisbursements,
                             Map<ReceiptType, BigDecimal> byType, Map<ReceiptType, Long> countByType,
                             Map<String, BigDecimal> byPaymentMethod, Map<String, BigDecimal> expensesByCategory,
                             BigDecimal incomeTotal, BigDecimal collectionsTotal, BigDecimal receiptsTotal,
                             BigDecimal expensesTotal, BigDecimal disbursementsTotal, BigDecimal net,
                             long newClients, long newMembers, long accountsOpened) {
        public BigDecimal getDeposits() {
            return byType.get(ReceiptType.ASSET_DEPOSIT);
        }

        public BigDecimal getRepayments() {
            return byType.get(ReceiptType.LOAN_REPAYMENT);
        }

        public BigDecimal getOutflowTotal() {
            return expensesTotal.add(disbursementsTotal);
        }

        public String getTitle() {
            String where = branch == null ? "All branches" : branch.getLabel();
            return from.equals(to) ? where + " — " + from.format(DAY) : where + " — " + from + " to " + to;
        }
    }

    public CashReport cashReport(LocalDate from, LocalDate to, Long branchId) {
        Branch branch = branchId == null ? null : branches.findById(branchId).orElse(null);
        // balances brought forward from the old register are not cash received in the period
        List<Receipt> rs = receipts.find(from, to, branchId, null).stream().filter(r -> !r.isReversed() && r.isCash())
                .sorted(Comparator.comparing(Receipt::getReceiptDate).thenComparing(Receipt::getId)).toList();
        List<Expense> allOut = expenses.find(from, to, branchId).stream()
                .sorted(Comparator.comparing(Expense::getExpenseDate).thenComparing(Expense::getId)).toList();
        List<Expense> es = allOut.stream().filter(e -> !e.isProjectDisbursement()).toList();
        List<Expense> oldDs = allOut.stream().filter(Expense::isProjectDisbursement).toList();
        List<Project> ds = projects.disbursedBetween(from, to, branchId);

        Map<ReceiptType, BigDecimal> byType = new EnumMap<>(ReceiptType.class);
        Map<ReceiptType, Long> count = new EnumMap<>(ReceiptType.class);
        for (ReceiptType t : ReceiptType.values()) {
            byType.put(t, BigDecimal.ZERO);
            count.put(t, 0L);
        }
        Map<String, BigDecimal> byMethod = new TreeMap<>();
        for (Receipt r : rs) {
            byType.merge(r.getType(), r.getAmount(), BigDecimal::add);
            count.merge(r.getType(), 1L, Long::sum);
            byMethod.merge(r.getPaymentMethod() == null ? "Cash" : r.getPaymentMethod(), r.getAmount(), BigDecimal::add);
        }
        Map<String, BigDecimal> byCat = new TreeMap<>();
        es.forEach(e -> byCat.merge(e.getCategory(), e.getAmount(), BigDecimal::add));

        BigDecimal income = sum(byType.entrySet().stream().filter(e -> e.getKey().isIncome()).map(Map.Entry::getValue));
        BigDecimal collections = sum(byType.entrySet().stream().filter(e -> !e.getKey().isIncome()).map(Map.Entry::getValue));
        BigDecimal total = income.add(collections);
        BigDecimal spent = sum(es.stream().map(Expense::getAmount));
        BigDecimal disbursed = sum(ds.stream().map(Project::getDisbursedAmount)).add(sum(oldDs.stream().map(Expense::getAmount)));

        return new CashReport(from, to, branch, rs, es, ds, oldDs, byType, count, byMethod, byCat,
                income, collections, total, spent, disbursed, total.subtract(spent).subtract(disbursed),
                clients.countRegistered(from, to, branchId), clients.countJoined(from, to, branchId),
                accounts.countOpened(from, to, branchId));
    }

    /** Plain-text version for pasting into the WhatsApp group. */
    public String whatsappText(CashReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("*ZimFete Asset Finance — ").append(r.from().equals(r.to()) ? "Daily" : "Period").append(" Report*\n");
        sb.append(r.getTitle()).append("\n\n");
        sb.append("*INFLOW*\n");
        for (ReceiptType t : ReceiptType.values()) {
            if (r.countByType().get(t) == 0) continue;
            sb.append("• ").append(t.getLabel()).append(" (").append(r.countByType().get(t)).append("): $")
                    .append(money(r.byType().get(t))).append("\n");
        }
        sb.append("Income (fees): $").append(money(r.incomeTotal())).append("\n");
        sb.append("Deposits & repayments: $").append(money(r.collectionsTotal())).append("\n");
        sb.append("*Total inflow: $").append(money(r.receiptsTotal())).append("*\n\n");
        sb.append("*OUTFLOW*\n");
        if (r.expenses().isEmpty() && r.disbursements().isEmpty()) sb.append("• None\n");
        r.expensesByCategory().forEach((k, v) -> sb.append("• ").append(k).append(": $").append(money(v)).append("\n"));
        r.disbursements().forEach(p -> sb.append("• Loan (project) ").append(p.getClient().getFullName()).append(", ")
                .append(p.getAssetLabel()).append(": $").append(money(p.getDisbursedAmount())).append("\n"));
        r.oldDisbursements().forEach(e -> sb.append("• Loan (project) ").append(e.getPayee() == null ? "" : e.getPayee()).append(", ")
                .append(e.getDescription() == null ? "" : e.getDescription()).append(": $").append(money(e.getAmount())).append("\n"));
        sb.append("*Total outflow: $").append(money(r.getOutflowTotal())).append("*\n\n");
        sb.append("*NET CASH: $").append(money(r.net())).append("*\n");
        sb.append("New SACCO members: ").append(r.newMembers()).append(" | New clients: ").append(r.newClients())
                .append(" | Accounts opened: ").append(r.accountsOpened()).append("\n");
        List<Receipt> deposits = r.receipts().stream().filter(x -> x.getType() == ReceiptType.ASSET_DEPOSIT).toList();
        if (!deposits.isEmpty()) {
            sb.append("\n*Deposits*\n");
            deposits.forEach(d -> sb.append("• ").append(d.getPayerName())
                    .append(d.getProject() != null ? " " + d.getProject().getLabel() : "")
                    .append(": $").append(money(d.getAmount())).append("\n"));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- monthly income & expenditure

    public record DayRow(LocalDate date, Map<ReceiptType, BigDecimal> byType, BigDecimal receipts,
                         BigDecimal expenses, BigDecimal disbursements, BigDecimal net) {
    }

    public record BranchRow(Branch branch, long newMembers, long accountsOpened, BigDecimal income,
                            BigDecimal deposits, BigDecimal repayments, BigDecimal expenses,
                            BigDecimal disbursements, BigDecimal net) {
    }

    public record MonthlyReport(YearMonth month, CashReport totals, List<DayRow> days, List<BranchRow> branchRows) {
        /** ZimFete's own result: fee income less operating expenditure (disbursements are loans, not costs). */
        public BigDecimal getSurplus() {
            return totals.incomeTotal().subtract(totals.expensesTotal());
        }
    }

    public MonthlyReport monthly(YearMonth month, Long branchId) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        CashReport totals = cashReport(from, to, branchId);

        List<DayRow> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            final LocalDate day = d;
            Map<ReceiptType, BigDecimal> m = new EnumMap<>(ReceiptType.class);
            for (ReceiptType t : ReceiptType.values()) m.put(t, BigDecimal.ZERO);
            totals.receipts().stream().filter(r -> r.getReceiptDate().equals(day)).forEach(r -> m.merge(r.getType(), r.getAmount(), BigDecimal::add));
            BigDecimal rec = sum(m.values().stream());
            BigDecimal exp = sum(totals.expenses().stream().filter(e -> e.getExpenseDate().equals(day)).map(Expense::getAmount));
            BigDecimal dis = sum(totals.disbursements().stream().filter(p -> p.getProjectStartDate().equals(day)).map(Project::getDisbursedAmount))
                    .add(sum(totals.oldDisbursements().stream().filter(e -> e.getExpenseDate().equals(day)).map(Expense::getAmount)));
            if (rec.signum() != 0 || exp.signum() != 0 || dis.signum() != 0) {
                days.add(new DayRow(day, m, rec, exp, dis, rec.subtract(exp).subtract(dis)));
            }
        }

        List<BranchRow> rows = new ArrayList<>();
        if (branchId == null) {
            for (Branch b : reportingBranches()) {
                CashReport c = cashReport(from, to, b.getId());
                rows.add(new BranchRow(b, c.newMembers(), c.accountsOpened(), c.incomeTotal(), c.getDeposits(),
                        c.getRepayments(), c.expensesTotal(), c.disbursementsTotal(), c.net()));
            }
        }
        return new MonthlyReport(month, totals, days, rows);
    }

    // ---------------------------------------------------------------- AFM dashboard

    public record DistrictStats(Branch branch, long clients, long members, long accounts, long activeAccounts,
                                long accountsThisMonth, BigDecimal depositsTotal, BigDecimal depositsThisMonth,
                                long saving, long awaitingApproval, long approved, long inProgress, long completed,
                                long due, BigDecimal loanBook, BigDecimal arrears) {
        /** All-districts total row. */
        public static DistrictStats total(List<DistrictStats> rows) {
            return new DistrictStats(null,
                    rows.stream().mapToLong(DistrictStats::clients).sum(),
                    rows.stream().mapToLong(DistrictStats::members).sum(),
                    rows.stream().mapToLong(DistrictStats::accounts).sum(),
                    rows.stream().mapToLong(DistrictStats::activeAccounts).sum(),
                    rows.stream().mapToLong(DistrictStats::accountsThisMonth).sum(),
                    sum(rows.stream().map(DistrictStats::depositsTotal)),
                    sum(rows.stream().map(DistrictStats::depositsThisMonth)),
                    rows.stream().mapToLong(DistrictStats::saving).sum(),
                    rows.stream().mapToLong(DistrictStats::awaitingApproval).sum(),
                    rows.stream().mapToLong(DistrictStats::approved).sum(),
                    rows.stream().mapToLong(DistrictStats::inProgress).sum(),
                    rows.stream().mapToLong(DistrictStats::completed).sum(),
                    rows.stream().mapToLong(DistrictStats::due).sum(),
                    sum(rows.stream().map(DistrictStats::loanBook)),
                    sum(rows.stream().map(DistrictStats::arrears)));
        }
    }

    public List<DistrictStats> districtStats(LocalDate today) {
        YearMonth ym = YearMonth.from(today);
        List<AssetAccount> allAccounts = accounts.findAll();
        List<Project> allProjects = projects.findAll();
        List<Receipt> monthDeposits = receipts.find(ym.atDay(1), ym.atEndOfMonth(), null, ReceiptType.ASSET_DEPOSIT);
        List<DistrictStats> out = new ArrayList<>();
        for (Branch b : reportingBranches()) {
            List<AssetAccount> as = allAccounts.stream().filter(a -> a.getBranch().getId().equals(b.getId())).toList();
            List<Project> ps = allProjects.stream().filter(p -> p.getBranch().getId().equals(b.getId())).toList();
            out.add(new DistrictStats(b,
                    clients.countByBranchId(b.getId()),
                    clients.countByBranchIdAndSaccoMemberTrue(b.getId()),
                    as.size(),
                    as.stream().filter(AssetAccount::isActive).count(),
                    as.stream().filter(a -> a.getOpenedDate() != null && YearMonth.from(a.getOpenedDate()).equals(ym)).count(),
                    sum(ps.stream().map(Project::getTotalDeposited)),
                    sum(monthDeposits.stream().filter(r -> !r.isReversed() && r.isCash() && r.getBranch().getId().equals(b.getId())).map(Receipt::getAmount)),
                    countStatus(ps, ProjectStatus.SAVING),
                    countStatus(ps, ProjectStatus.THRESHOLD_MET),
                    countStatus(ps, ProjectStatus.APPROVED),
                    countStatus(ps, ProjectStatus.IN_PROGRESS),
                    countStatus(ps, ProjectStatus.COMPLETED),
                    ps.stream().filter(p -> isDue(p, today, 14)).count(),
                    sum(ps.stream().filter(Project::isLoanStarted).map(Project::getLoanBalance)),
                    sum(ps.stream().map(p -> p.getArrears(today)))));
        }
        return out;
    }

    /** SACCO members per district and category: who will be eligible when the grant comes. */
    public record MembershipRow(Branch branch, Map<MemberCategory, Long> byCategory, long total) {
    }

    public List<MembershipRow> membership() {
        List<Client> members = clients.search(null, null, true, null);
        List<MembershipRow> rows = new ArrayList<>();
        for (Branch b : reportingBranches()) {
            Map<MemberCategory, Long> m = new EnumMap<>(MemberCategory.class);
            for (MemberCategory c : MemberCategory.values()) if (c.isVeteranCommunity()) m.put(c, 0L);
            members.stream().filter(c -> c.getBranch().getId().equals(b.getId())).forEach(c -> m.merge(c.getCategory(), 1L, Long::sum));
            rows.add(new MembershipRow(b, m, m.values().stream().mapToLong(Long::longValue).sum()));
        }
        return rows;
    }

    /** Operating branches, plus any historical location (e.g. Harare) that still has records. */
    public List<Branch> reportingBranches() {
        return branches.findAllByOrderByHeadOfficeDescNameAsc().stream()
                .filter(b -> b.isOperating() || clients.countByBranchId(b.getId()) > 0).toList();
    }

    /** Not yet started and the client's target date falls within {@code withinDays} (or has passed). */
    public static boolean isDue(Project p, LocalDate today, int withinDays) {
        return p.getStatus().isPreStart() && p.getTargetDate() != null && !p.getTargetDate().isAfter(today.plusDays(withinDays));
    }

    public List<Project> dueProjects(LocalDate today, int withinDays, Long branchId) {
        return projects.findAll().stream()
                .filter(p -> branchId == null || p.getBranch().getId().equals(branchId))
                .filter(p -> isDue(p, today, withinDays))
                .sorted(Comparator.comparing(Project::getTargetDate)).toList();
    }

    public List<Project> byStatus(ProjectStatus status, Long branchId) {
        return projects.findAllOrdered().stream()
                .filter(p -> branchId == null || p.getBranch().getId().equals(branchId))
                .filter(p -> status == null || p.getStatus() == status).toList();
    }

    public Map<String, Long> accountsOpenedBy(List<AssetAccount> list) {
        return list.stream().collect(Collectors.groupingBy(a -> a.getOpenedBy() == null ? "(not recorded)" : a.getOpenedBy(),
                TreeMap::new, Collectors.counting()));
    }

    // ---------------------------------------------------------------- helpers

    private static long countStatus(List<Project> ps, ProjectStatus s) {
        return ps.stream().filter(p -> p.getStatus() == s).count();
    }

    public static BigDecimal sum(Stream<BigDecimal> s) {
        return s.filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static String money(BigDecimal b) {
        return String.format("%,.2f", b == null ? BigDecimal.ZERO : b);
    }
}
