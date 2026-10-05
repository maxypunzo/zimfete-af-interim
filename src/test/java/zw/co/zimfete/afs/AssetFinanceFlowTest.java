package zw.co.zimfete.afs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.*;
import zw.co.zimfete.afs.service.*;

@SpringBootTest
@Transactional
class AssetFinanceFlowTest {
    @Autowired ClientService clientService;
    @Autowired AccountService accountService;
    @Autowired ProjectService projectService;
    @Autowired ReceiptService receiptService;
    @Autowired ReportService reportService;
    @Autowired BranchRepository branches;
    @Autowired AssetAccountRepository accounts;
    @Autowired ProjectRepository projects;
    @Autowired ExpenseRepository expenseRepository;

    private final LocalDate day = LocalDate.now().minusDays(3);

    private RegistrationRequest person(String name, MemberCategory category) {
        RegistrationRequest r = new RegistrationRequest();
        r.setBranchId(branches.findByCode("MRE").orElseThrow().getId());
        r.setFirstName(name);
        r.setSurname("Moyo");
        r.setCategory(category);
        r.setWard("Ward 7");
        r.setDateRegistered(day);
        r.setCapturedBy("Clerk A");
        return r;
    }

    private RegistrationRequest withAccount(RegistrationRequest r, String cost, String deposit) {
        r.setOpenAccount(true);
        r.getProject().setAssetType(AssetType.BOREHOLE);
        r.getProject().setQuotationCost(new BigDecimal(cost));
        r.getProject().setInitialDeposit(new BigDecimal(deposit));
        return r;
    }

    @Test
    void veteranJoinsTheSaccoAndPaysMembershipFees() {
        RegistrationRequest r = person("Tendai", MemberCategory.WAR_VETERAN);
        r.setJoinSacco(true);
        r.getMembership().setSubsMonths(2);
        Client c = clientService.register(r, "MANUAL");

        assertThat(c.getClientNo()).isEqualTo("MRE-C00001");
        assertThat(c.isSaccoMember()).isTrue();
        assertThat(c.getMemberSince()).isEqualTo(day);
        assertThat(c.isJoiningFeePaid()).isTrue();
        assertThat(c.getSubsPaidUntil()).isEqualTo(day.withDayOfMonth(1).plusMonths(1));
        assertThat(accounts.findByClientIdOrderByOpenedDateDesc(c.getId())).isEmpty(); // member only, no asset finance

        ReportService.CashReport rep = reportService.cashReport(day, day, null);
        assertThat(rep.incomeTotal()).isEqualByComparingTo("12");
        assertThat(rep.newMembers()).isEqualTo(1);
    }

    @Test
    void membershipIsForTheVeteranCommunityOnly() {
        RegistrationRequest r = person("Farai", MemberCategory.NOT_VETERAN);
        r.setJoinSacco(true);
        assertThatThrownBy(() -> clientService.register(r, "MANUAL")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("veteran community");
    }

    @Test
    void nonMemberOpensAnAccountWithoutMembershipFees() {
        Client c = clientService.register(withAccount(person("Rudo", MemberCategory.NOT_VETERAN), "4000", "500"), "MANUAL");
        assertThat(c.isSaccoMember()).isFalse();
        assertThat(c.subsMonthsOwed(LocalDate.now())).isZero();

        AssetAccount a = accounts.findByClientIdOrderByOpenedDateDesc(c.getId()).get(0);
        assertThat(a.getAccountNo()).isEqualTo("MRE" + String.format("%02d", day.getYear() % 100) + "01ME");
        assertThat(a.isActive()).isTrue();

        ReceiptRequest subs = new ReceiptRequest();
        subs.setClientId(c.getId());
        subs.setType(ReceiptType.SUBSCRIPTION);
        subs.setAmount(BigDecimal.ONE);
        assertThatThrownBy(() -> receiptService.record(subs)).hasMessageContaining("not a SACCO member");

        ReportService.CashReport rep = reportService.cashReport(day, day, null);
        assertThat(rep.incomeTotal()).isEqualByComparingTo("50");
        assertThat(rep.collectionsTotal()).isEqualByComparingTo("500");
        assertThat(rep.newMembers()).isZero();
        assertThat(rep.newClients()).isEqualTo(1);
    }

    @Test
    void openingFeeCanBePaidInInstalments() {
        RegistrationRequest r = person("Rudo", MemberCategory.NOT_VETERAN);
        r.setOpenAccount(true);
        r.getAccount().setOpeningFeeAmount(new BigDecimal("20"));
        Client c = clientService.register(r, "MANUAL");
        AssetAccount a = accounts.findByClientIdOrderByOpenedDateDesc(c.getId()).get(0);
        assertThat(a.isActive()).isFalse();
        assertThat(a.getOpeningFeeBalance()).isEqualByComparingTo("30");

        ReceiptRequest more = new ReceiptRequest();
        more.setAccountId(a.getId());
        more.setType(ReceiptType.ACCOUNT_OPENING);
        more.setAmount(new BigDecimal("40"));
        assertThatThrownBy(() -> receiptService.record(more)).hasMessageContaining("Only $30.00");
        more.setAmount(new BigDecimal("30"));
        receiptService.record(more);
        assertThat(a.isActive()).isTrue();
        assertThat(a.getActivatedDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void oneAccountCarriesSeveralProjects() {
        Client c = clientService.register(withAccount(person("Viola", MemberCategory.WIDOW), "2140", "1070"), "MANUAL");
        AssetAccount a = accounts.findByClientIdOrderByOpenedDateDesc(c.getId()).get(0);
        ProjectRequest fence = new ProjectRequest();
        fence.setAssetType(AssetType.FENCING);
        fence.setQuotationCost(new BigDecimal("1000"));
        fence.setInitialDeposit(new BigDecimal("500"));
        projectService.create(a.getId(), fence, "MANUAL");

        assertThat(projects.findByAccountIdOrderByIdAsc(a.getId())).hasSize(2)
                .allMatch(p -> p.getStatus() == ProjectStatus.THRESHOLD_MET);

        ReceiptRequest ambiguous = new ReceiptRequest();
        ambiguous.setAccountId(a.getId());
        ambiguous.setType(ReceiptType.ASSET_DEPOSIT);
        ambiguous.setAmount(BigDecimal.TEN);
        assertThatThrownBy(() -> receiptService.record(ambiguous)).hasMessageContaining("choose which one");
    }

    @Test
    void projectNeedsCommitteeApprovalThenDisbursementFixesTheLoan() {
        Client c = clientService.register(withAccount(person("Cephas", MemberCategory.WAR_COLLABORATOR), "1825", "500"), "MANUAL");
        Project p = projects.findByClient(c.getId()).get(0);
        assertThat(p.getStatus()).isEqualTo(ProjectStatus.SAVING);

        assertThatThrownBy(() -> projectService.approve(p.getId(), day, null)).hasMessageContaining("committee's reason");
        deposit(p, "460"); // 960 >= 912.50
        assertThat(p.getStatus()).isEqualTo(ProjectStatus.THRESHOLD_MET);
        assertThat(reportService.byStatus(ProjectStatus.THRESHOLD_MET, null)).contains(p);

        assertThatThrownBy(() -> projectService.start(p.getId(), day, 4, null, null, null)).hasMessageContaining("committee must approve");
        projectService.approve(p.getId(), day, null);
        projectService.start(p.getId(), day, 4, new BigDecimal("20"), null, "Drillers Ltd");
        assertThat(p.getStatus()).isEqualTo(ProjectStatus.IN_PROGRESS);
        assertThat(p.getLoanTerms().totalRepayable()).isEqualByComparingTo("1038.00");
        assertThat(p.getDisbursedAmount()).isEqualByComparingTo("1825");

        ReportService.CashReport rep = reportService.cashReport(day, day, null);
        assertThat(rep.disbursementsTotal()).isEqualByComparingTo("1825");
        assertThat(rep.net()).isEqualByComparingTo(rep.receiptsTotal().subtract(new BigDecimal("1825")));
        assertThat(reportService.whatsappText(rep)).contains("Loan (project) Cephas Moyo");

        assertThatThrownBy(() -> deposit(p, "10")).hasMessageContaining("loan repayments");
        projectService.complete(p.getId(), day.plusDays(2));
        assertThat(p.getDaysToComplete()).isEqualTo(2);

        Receipt rep1 = post(p, ReceiptType.LOAN_REPAYMENT, "1038");
        assertThat(p.getLoanBalance()).isEqualByComparingTo("0");
        assertThat(p.getLoanClearedDate()).isEqualTo(day);
        receiptService.reverse(rep1.getId(), "wrong project");
        assertThat(p.getLoanBalance()).isEqualByComparingTo("1038");
        assertThat(p.getLoanClearedDate()).isNull();
    }

    @Test
    void committeeCanApproveBelowTheMinimumWithAReason() {
        Client c = clientService.register(withAccount(person("Patricia", MemberCategory.NOT_VETERAN), "110", "0.01"), "MANUAL");
        Project p = projects.findByClient(c.getId()).get(0);
        projectService.approve(p.getId(), day, "Committee: poultry pilot");
        assertThat(p.getStatus()).isEqualTo(ProjectStatus.APPROVED);
        assertThat(p.getApprovalNote()).isEqualTo("Committee: poultry pilot");
    }

    @Test
    void monthlyReportSeparatesIncomeClientFundsAndDisbursements() {
        clientService.register(withAccount(person("Febby", MemberCategory.NOT_VETERAN), "2325", "900"), "MANUAL");
        Expense e = new Expense();
        e.setBranch(branches.findByCode("MRE").orElseThrow());
        e.setExpenseDate(day);
        e.setCategory("Airtime & travel");
        e.setAmount(new BigDecimal("1"));
        expenseRepository.save(e);

        ReportService.MonthlyReport m = reportService.monthly(YearMonth.from(day), null);
        assertThat(m.totals().incomeTotal()).isEqualByComparingTo("50");
        assertThat(m.getSurplus()).isEqualByComparingTo("49");
        assertThat(m.totals().getDeposits()).isEqualByComparingTo("900");
        assertThat(m.branchRows()).hasSize(7);
        assertThat(m.days()).hasSize(1);
    }

    private Receipt deposit(Project p, String amount) {
        return post(p, ReceiptType.ASSET_DEPOSIT, amount);
    }

    private Receipt post(Project p, ReceiptType type, String amount) {
        ReceiptRequest r = new ReceiptRequest();
        r.setProjectId(p.getId());
        r.setType(type);
        r.setAmount(new BigDecimal(amount));
        r.setReceiptDate(day);
        return receiptService.record(r);
    }
}
