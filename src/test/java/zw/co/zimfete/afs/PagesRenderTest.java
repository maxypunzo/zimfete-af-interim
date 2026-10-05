package zw.co.zimfete.afs;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.*;
import zw.co.zimfete.afs.service.*;

/** Renders every screen against real data so template mistakes fail the build. */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PagesRenderTest {
    @Autowired MockMvc mvc;
    @Autowired MemberService memberService;
    @Autowired AccountService accountService;
    @Autowired ReceiptService receiptService;
    @Autowired BranchRepository branches;
    @Autowired AssetAccountRepository accounts;
    @Autowired ReceiptRepository receipts;

    @Test
    void allPagesRender() throws Exception {
        LocalDate d = LocalDate.now();
        RegistrationRequest r = new RegistrationRequest();
        r.setBranchId(branches.findByCode("GMZ").orElseThrow().getId());
        r.setFirstName("Farai");
        r.setSurname("Ncube");
        r.setNationalId("29-777777G88");
        r.setDateJoined(d.minusMonths(3));
        r.setOpenAccount(true);
        r.getAccount().setQuotationCost(new BigDecimal("2000"));
        r.getAccount().setInitialDeposit(new BigDecimal("1000"));
        r.getAccount().setTargetDate(d.plusDays(5));
        r.getAccount().setAssetType(AssetType.FENCING);
        Member m = memberService.register(r, "MANUAL");
        AssetAccount a = accounts.findByMemberIdOrderByOpenedDateDesc(m.getId()).get(0);
        accountService.startProject(a.getId(), d.minusMonths(2), 6);
        ReceiptRequest rep = new ReceiptRequest();
        rep.setAccountId(a.getId());
        rep.setType(ReceiptType.LOAN_REPAYMENT);
        rep.setAmount(new BigDecimal("100"));
        receiptService.record(rep);
        Long receiptId = receipts.findByMemberIdOrderByReceiptDateDescIdDesc(m.getId()).get(0).getId();

        String[] pages = {
                "/", "/members", "/members?q=farai", "/members/new", "/members/" + m.getId(), "/members/" + m.getId() + "/edit",
                "/members/" + m.getId() + "/open-account", "/accounts/" + a.getId(), "/accounts/" + a.getId() + "/edit",
                "/receipts", "/receipts/new", "/receipts/new?memberId=" + m.getId(), "/receipts/new?accountId=" + a.getId(),
                "/receipts/" + receiptId, "/expenses", "/reports/daily", "/reports/daily?all=true&from=" + d.minusMonths(3) + "&to=" + d,
                "/reports/monthly", "/register", "/register?tab=deposits", "/register?tab=projects", "/register?tab=projects&view=due",
                "/register?tab=loans", "/register?tab=subs", "/import", "/branches"
        };
        for (String p : pages) {
            mvc.perform(get(p)).andExpect(status().isOk());
        }
        // date inputs need ISO values or browsers show them blank and refuse to submit
        mvc.perform(get("/members/new")).andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"" + d + "\"")));
        mvc.perform(get("/reports/register.xlsx")).andExpect(status().isOk());
        mvc.perform(get("/reports/monthly.xlsx")).andExpect(status().isOk());
        mvc.perform(get("/import/template?branchId=" + a.getBranch().getId())).andExpect(status().isOk());

        // receipt by typing the national ID
        mvc.perform(post("/receipts/new").with(csrf()).param("lookup", "29-777777G88").param("type", "SUBSCRIPTION")
                        .param("amount", "2").param("receiptDate", d.toString()).param("paymentMethod", "Cash"))
                .andExpect(status().is3xxRedirection());
        // a validation error re-renders the form
        mvc.perform(post("/receipts/new").with(csrf()).param("lookup", "nobody").param("type", "SUBSCRIPTION")
                        .param("amount", "2").param("receiptDate", d.toString()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("No member or account")));
    }
}
