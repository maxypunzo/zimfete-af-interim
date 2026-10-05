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
    @Autowired MemberService memberService;
    @Autowired AccountService accountService;
    @Autowired ReceiptService receiptService;
    @Autowired ReportService reportService;
    @Autowired BranchRepository branches;
    @Autowired AssetAccountRepository accounts;
    @Autowired ReceiptRepository receipts;

    private final LocalDate day = LocalDate.now().minusDays(3);

    private Member register(String id, boolean openAccount) {
        RegistrationRequest r = new RegistrationRequest();
        r.setBranchId(branches.findByCode("MRW").orElseThrow().getId());
        r.setFirstName("Tendai");
        r.setSurname("Moyo");
        r.setNationalId(id);
        r.setWard("12");
        r.setDateJoined(day);
        r.setCapturedBy("Clerk A");
        r.setSubsMonths(2);
        r.setOpenAccount(openAccount);
        r.getAccount().setAssetType(AssetType.BOREHOLE);
        r.getAccount().setQuotationCost(new BigDecimal("4000"));
        r.getAccount().setInitialDeposit(new BigDecimal("500"));
        return memberService.register(r, "MANUAL");
    }

    @Test
    void registeringAMemberPostsFeesAndOpensAccountInOneGo() {
        Member m = register("63-123456 A 75", true);

        assertThat(m.getMemberNo()).isEqualTo("MRW-M00001");
        assertThat(m.getNationalId()).isEqualTo("63123456A75");
        assertThat(m.isJoiningFeePaid()).isTrue();
        assertThat(m.getSubsPaidUntil()).isEqualTo(day.withDayOfMonth(1).plusMonths(1));

        AssetAccount a = accounts.findByMemberIdOrderByOpenedDateDesc(m.getId()).get(0);
        assertThat(a.getAccountNo()).isEqualTo("AF-MRW-0001");
        assertThat(a.getOpenedBy()).isEqualTo("Clerk A");
        assertThat(a.getTotalDeposited()).isEqualByComparingTo("500");
        assertThat(a.getStatus()).isEqualTo(ProjectStatus.SAVING);

        // joining 10 + subs 2 + opening 50 = 62 income; 500 deposit is client funds
        ReportService.CashReport r = reportService.cashReport(day, day, null);
        assertThat(r.incomeTotal()).isEqualByComparingTo("62");
        assertThat(r.collectionsTotal()).isEqualByComparingTo("500");
        assertThat(r.newMembers()).isEqualTo(1);
        assertThat(r.accountsOpened()).isEqualTo(1);
        assertThat(reportService.whatsappText(r)).contains("Total received: $562.00");
    }

    @Test
    void duplicateNationalIdIsRejected() {
        register("63-111111B22", false);
        assertThatThrownBy(() -> register("63111111b22", false)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void projectLifecycleFromDepositsToLoanClearance() {
        Member m = register("70-222222C33", true);
        AssetAccount a = accounts.findByMemberIdOrderByOpenedDateDesc(m.getId()).get(0);

        assertThatThrownBy(() -> accountService.startProject(a.getId(), day, 10)).isInstanceOf(BusinessException.class);

        deposit(a, "1500"); // 500 + 1500 = 2000 = 50% of 4000
        assertThat(a.getStatus()).isEqualTo(ProjectStatus.THRESHOLD_MET);
        assertThat(a.getThresholdReachedDate()).isEqualTo(day);
        assertThat(reportService.byStatus(ProjectStatus.THRESHOLD_MET, null)).contains(a);

        accountService.startProject(a.getId(), day, 10);
        assertThat(a.getLoanTerms().totalRepayable()).isEqualByComparingTo("2600.00"); // (4000-2000) * 1.3
        assertThat(a.getLoanTerms().monthlyInstalment()).isEqualByComparingTo("260.00");

        assertThatThrownBy(() -> deposit(a, "10")).isInstanceOf(BusinessException.class);
        accountService.complete(a.getId(), day.plusDays(2));
        assertThat(a.getStatus()).isEqualTo(ProjectStatus.COMPLETED);
        assertThat(a.getDaysToComplete()).isEqualTo(2);

        Receipt rep = repay(a, "2600");
        assertThat(a.getLoanBalance()).isEqualByComparingTo("0");
        assertThat(a.getLoanClearedDate()).isEqualTo(day);

        receiptService.reverse(rep.getId(), "wrong account");
        assertThat(a.getLoanBalance()).isEqualByComparingTo("2600");
        assertThat(a.getLoanClearedDate()).isNull();
    }

    @Test
    void reversingADepositDropsTheAccountBelowThreshold() {
        Member m = register("75-333333D44", true);
        AssetAccount a = accounts.findByMemberIdOrderByOpenedDateDesc(m.getId()).get(0);
        Receipt big = deposit(a, "1500");
        assertThat(a.getStatus()).isEqualTo(ProjectStatus.THRESHOLD_MET);
        receiptService.reverse(big.getId(), "bounced");
        assertThat(a.getStatus()).isEqualTo(ProjectStatus.SAVING);
        assertThat(a.getTotalDeposited()).isEqualByComparingTo("500");
    }

    @Test
    void monthlyReportSplitsIncomeFromClientFunds() {
        register("80-444444E55", true);
        Branch mrw = branches.findByCode("MRW").orElseThrow();
        Expense e = new Expense();
        e.setBranch(mrw);
        e.setExpenseDate(day);
        e.setCategory("Transport");
        e.setAmount(new BigDecimal("20"));
        expenseRepository.save(e);

        ReportService.MonthlyReport m = reportService.monthly(YearMonth.from(day), null);
        assertThat(m.totals().incomeTotal()).isEqualByComparingTo("62");
        assertThat(m.getSurplus()).isEqualByComparingTo("42");
        assertThat(m.totals().getDeposits()).isEqualByComparingTo("500");
        assertThat(m.branchRows()).hasSize(7);
        assertThat(m.days()).hasSize(1);
    }

    @Autowired ExpenseRepository expenseRepository;

    private Receipt deposit(AssetAccount a, String amount) {
        return post(a, ReceiptType.ASSET_DEPOSIT, amount);
    }

    private Receipt repay(AssetAccount a, String amount) {
        return post(a, ReceiptType.LOAN_REPAYMENT, amount);
    }

    private Receipt post(AssetAccount a, ReceiptType type, String amount) {
        ReceiptRequest r = new ReceiptRequest();
        r.setAccountId(a.getId());
        r.setType(type);
        r.setAmount(new BigDecimal(amount));
        r.setReceiptDate(day);
        return receiptService.record(r);
    }
}
