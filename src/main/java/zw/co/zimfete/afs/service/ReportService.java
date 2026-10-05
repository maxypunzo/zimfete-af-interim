package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
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
    private final MemberRepository members;
    private final AssetAccountRepository accounts;
    private final BranchRepository branches;

    public ReportService(ReceiptRepository receipts, ExpenseRepository expenses, MemberRepository members,
                         AssetAccountRepository accounts, BranchRepository branches) {
        this.receipts = receipts;
        this.expenses = expenses;
        this.members = members;
        this.accounts = accounts;
        this.branches = branches;
    }

    // ---------------------------------------------------------------- cash report (daily or any period)

    public record CashReport(LocalDate from, LocalDate to, Branch branch,
                             List<Receipt> receipts, List<Expense> expenses,
                             Map<ReceiptType, BigDecimal> byType, Map<ReceiptType, Long> countByType,
                             Map<String, BigDecimal> byPaymentMethod, Map<String, BigDecimal> expensesByCategory,
                             BigDecimal incomeTotal, BigDecimal collectionsTotal, BigDecimal receiptsTotal,
                             BigDecimal expensesTotal, BigDecimal net,
                             long newMembers, long accountsOpened) {
        public BigDecimal getDeposits() {
            return byType.get(ReceiptType.ASSET_DEPOSIT);
        }

        public BigDecimal getRepayments() {
            return byType.get(ReceiptType.LOAN_REPAYMENT);
        }

        public String getTitle() {
            String where = branch == null ? "All branches" : branch.getLabel();
            return from.equals(to) ? where + " — " + from.format(DAY) : where + " — " + from + " to " + to;
        }
    }

    public CashReport cashReport(LocalDate from, LocalDate to, Long branchId) {
        Branch branch = branchId == null ? null : branches.findById(branchId).orElse(null);
        List<Receipt> rs = receipts.find(from, to, branchId, null).stream().filter(r -> !r.isReversed())
                .sorted(Comparator.comparing(Receipt::getReceiptDate).thenComparing(Receipt::getId)).toList();
        List<Expense> es = expenses.find(from, to, branchId).stream()
                .sorted(Comparator.comparing(Expense::getExpenseDate).thenComparing(Expense::getId)).toList();

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

        long newMembers = members.countJoined(from, to, branchId);
        long opened = accounts.countOpened(from, to, branchId);

        return new CashReport(from, to, branch, rs, es, byType, count, byMethod, byCat,
                income, collections, total, spent, total.subtract(spent), newMembers, opened);
    }

    /** Plain-text version for pasting into the WhatsApp group. */
    public String whatsappText(CashReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("*ZimFete Asset Finance — ").append(r.from().equals(r.to()) ? "Daily" : "Period").append(" Report*\n");
        sb.append(r.getTitle()).append("\n\n");
        sb.append("*RECEIPTS*\n");
        for (ReceiptType t : ReceiptType.values()) {
            if (r.countByType().get(t) == 0) continue;
            sb.append("• ").append(t.getLabel()).append(" (").append(r.countByType().get(t)).append("): $")
                    .append(money(r.byType().get(t))).append("\n");
        }
        sb.append("Income (fees): $").append(money(r.incomeTotal())).append("\n");
        sb.append("Deposits & repayments: $").append(money(r.collectionsTotal())).append("\n");
        sb.append("*Total received: $").append(money(r.receiptsTotal())).append("*\n\n");
        sb.append("*EXPENDITURE*\n");
        if (r.expenses().isEmpty()) sb.append("• None\n");
        r.expensesByCategory().forEach((k, v) -> sb.append("• ").append(k).append(": $").append(money(v)).append("\n"));
        sb.append("*Total spent: $").append(money(r.expensesTotal())).append("*\n\n");
        sb.append("*NET CASH: $").append(money(r.net())).append("*\n");
        sb.append("New members: ").append(r.newMembers()).append(" | Accounts opened: ").append(r.accountsOpened()).append("\n");
        List<Receipt> deposits = r.receipts().stream().filter(x -> x.getType() == ReceiptType.ASSET_DEPOSIT).toList();
        if (!deposits.isEmpty()) {
            sb.append("\n*Deposits*\n");
            deposits.forEach(d -> sb.append("• ").append(d.getPayerName()).append(" ").append(d.getAccount().getAccountNo())
                    .append(" (").append(d.getAccount().getAssetType() == null ? "asset" : d.getAccount().getAssetType().getLabel())
                    .append("): $").append(money(d.getAmount())).append("\n"));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- monthly income & expenditure

    public record DayRow(LocalDate date, Map<ReceiptType, BigDecimal> byType, BigDecimal receipts,
                         BigDecimal expenses, BigDecimal net) {
    }

    public record BranchRow(Branch branch, long newMembers, long accountsOpened, BigDecimal income,
                            BigDecimal deposits, BigDecimal repayments, BigDecimal expenses, BigDecimal net) {
    }

    public record MonthlyReport(YearMonth month, CashReport totals, List<DayRow> days, List<BranchRow> branchRows) {
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
            if (rec.signum() != 0 || exp.signum() != 0) days.add(new DayRow(day, m, rec, exp, rec.subtract(exp)));
        }

        List<BranchRow> rows = new ArrayList<>();
        if (branchId == null) {
            for (Branch b : branches.findAllByOrderByHeadOfficeDescNameAsc()) {
                CashReport c = cashReport(from, to, b.getId());
                rows.add(new BranchRow(b, c.newMembers(), c.accountsOpened(), c.incomeTotal(),
                        c.byType().get(ReceiptType.ASSET_DEPOSIT), c.byType().get(ReceiptType.LOAN_REPAYMENT),
                        c.expensesTotal(), c.net()));
            }
        }
        return new MonthlyReport(month, totals, days, rows);
    }

    // ---------------------------------------------------------------- AFM dashboard

    public record DistrictStats(Branch branch, long members, long accounts, long accountsThisMonth,
                                BigDecimal depositsTotal, BigDecimal depositsThisMonth,
                                long saving, long thresholdMet, long inProgress, long completed,
                                long due, BigDecimal loanBook, BigDecimal arrears) {
        /** All-districts total row. */
        public static DistrictStats total(List<DistrictStats> rows) {
            return new DistrictStats(null,
                    rows.stream().mapToLong(DistrictStats::members).sum(),
                    rows.stream().mapToLong(DistrictStats::accounts).sum(),
                    rows.stream().mapToLong(DistrictStats::accountsThisMonth).sum(),
                    sum(rows.stream().map(DistrictStats::depositsTotal)),
                    sum(rows.stream().map(DistrictStats::depositsThisMonth)),
                    rows.stream().mapToLong(DistrictStats::saving).sum(),
                    rows.stream().mapToLong(DistrictStats::thresholdMet).sum(),
                    rows.stream().mapToLong(DistrictStats::inProgress).sum(),
                    rows.stream().mapToLong(DistrictStats::completed).sum(),
                    rows.stream().mapToLong(DistrictStats::due).sum(),
                    sum(rows.stream().map(DistrictStats::loanBook)),
                    sum(rows.stream().map(DistrictStats::arrears)));
        }
    }

    public List<DistrictStats> districtStats(LocalDate today) {
        YearMonth ym = YearMonth.from(today);
        List<AssetAccount> all = accounts.findAll();
        List<Receipt> monthReceipts = receipts.find(ym.atDay(1), ym.atEndOfMonth(), null, ReceiptType.ASSET_DEPOSIT);
        List<DistrictStats> out = new ArrayList<>();
        for (Branch b : branches.findAllByOrderByHeadOfficeDescNameAsc()) {
            List<AssetAccount> as = all.stream().filter(a -> a.getBranch().getId().equals(b.getId())).toList();
            out.add(new DistrictStats(b,
                    members.countByBranchId(b.getId()),
                    as.size(),
                    as.stream().filter(a -> YearMonth.from(a.getOpenedDate()).equals(ym)).count(),
                    sum(as.stream().map(AssetAccount::getTotalDeposited)),
                    sum(monthReceipts.stream().filter(r -> !r.isReversed() && r.getBranch().getId().equals(b.getId())).map(Receipt::getAmount)),
                    countStatus(as, ProjectStatus.SAVING),
                    countStatus(as, ProjectStatus.THRESHOLD_MET),
                    countStatus(as, ProjectStatus.IN_PROGRESS),
                    countStatus(as, ProjectStatus.COMPLETED),
                    as.stream().filter(a -> isDue(a, today, 14)).count(),
                    sum(as.stream().filter(AssetAccount::isLoanStarted).map(AssetAccount::getLoanBalance)),
                    sum(as.stream().map(a -> a.getArrears(today)))));
        }
        return out;
    }

    /** Not yet started and the client's target date falls within {@code withinDays} (or has passed). */
    public static boolean isDue(AssetAccount a, LocalDate today, int withinDays) {
        return (a.getStatus() == ProjectStatus.SAVING || a.getStatus() == ProjectStatus.THRESHOLD_MET)
                && a.getTargetDate() != null && !a.getTargetDate().isAfter(today.plusDays(withinDays));
    }

    public List<AssetAccount> dueProjects(LocalDate today, int withinDays, Long branchId) {
        return accounts.findAll().stream()
                .filter(a -> branchId == null || a.getBranch().getId().equals(branchId))
                .filter(a -> isDue(a, today, withinDays))
                .sorted(Comparator.comparing(AssetAccount::getTargetDate)).toList();
    }

    public List<AssetAccount> byStatus(ProjectStatus status, Long branchId) {
        return accounts.findAllByOrderByOpenedDateDescIdDesc().stream()
                .filter(a -> branchId == null || a.getBranch().getId().equals(branchId))
                .filter(a -> status == null || a.getStatus() == status).toList();
    }

    public Map<String, Long> accountsOpenedBy(List<AssetAccount> list) {
        return list.stream().collect(Collectors.groupingBy(a -> a.getOpenedBy() == null ? "(not recorded)" : a.getOpenedBy(),
                TreeMap::new, Collectors.counting()));
    }

    // ---------------------------------------------------------------- helpers

    private static long countStatus(List<AssetAccount> as, ProjectStatus s) {
        return as.stream().filter(a -> a.getStatus() == s).count();
    }

    public static BigDecimal sum(java.util.stream.Stream<BigDecimal> s) {
        return s.filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static String money(BigDecimal b) {
        return String.format("%,.2f", b == null ? BigDecimal.ZERO : b);
    }
}
