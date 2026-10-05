package zw.co.zimfete.afs;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.hamcrest.Matchers;
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
    @Autowired ClientService clientService;
    @Autowired ProjectService projectService;
    @Autowired ReceiptService receiptService;
    @Autowired BranchRepository branches;
    @Autowired AssetAccountRepository accounts;
    @Autowired ProjectRepository projects;
    @Autowired ReceiptRepository receipts;

    @Test
    void allPagesRender() throws Exception {
        LocalDate d = LocalDate.now();
        RegistrationRequest r = new RegistrationRequest();
        r.setBranchId(branches.findByCode("GMZ").orElseThrow().getId());
        r.setFirstName("Farai");
        r.setSurname("Ncube");
        r.setNationalId("29-777777G88");
        r.setCategory(MemberCategory.DESCENDANT);
        r.setDateRegistered(d.minusMonths(3));
        r.setJoinSacco(true);
        r.setOpenAccount(true);
        r.getAccount().setOpeningFeeAmount(new BigDecimal("30"));
        r.getProject().setQuotationCost(new BigDecimal("2000"));
        r.getProject().setInitialDeposit(new BigDecimal("1000"));
        r.getProject().setTargetDate(d.plusDays(5));
        r.getProject().setAssetType(AssetType.FENCING);
        Client c = clientService.register(r, "MANUAL");
        AssetAccount a = accounts.findByClientIdOrderByOpenedDateDesc(c.getId()).get(0);
        Project p = projects.findByAccountIdOrderByIdAsc(a.getId()).get(0);
        ProjectRequest second = new ProjectRequest();
        second.setAssetType(AssetType.SOLAR);
        second.setQuotationCost(new BigDecimal("900"));
        Project p2 = projectService.create(a.getId(), second, "MANUAL");
        projectService.approve(p.getId(), d.minusMonths(2), null);
        projectService.start(p.getId(), d.minusMonths(2), 6, null, null, "Fence Co", null);
        ReceiptRequest rep = new ReceiptRequest();
        rep.setProjectId(p.getId());
        rep.setType(ReceiptType.LOAN_REPAYMENT);
        rep.setAmount(new BigDecimal("100"));
        receiptService.record(rep);
        Long receiptId = receipts.findByClientIdOrderByReceiptDateDescIdDesc(c.getId()).get(0).getId();

        String[] pages = {
                "/", "/clients", "/clients?q=farai", "/clients?members=true&category=DESCENDANT", "/clients/new", "/clients/new?member=true",
                "/clients/" + c.getId(), "/clients/" + c.getId() + "/edit", "/clients/" + c.getId() + "/enrol",
                "/clients/" + c.getId() + "/open-account", "/accounts/" + a.getId(), "/accounts/" + a.getId() + "/projects/new",
                "/projects/" + p.getId(), "/projects/" + p.getId() + "/edit", "/projects/" + p2.getId(), "/projects/" + p2.getId() + "?months=6&rate=20",
                "/receipts", "/receipts/new", "/receipts/new?clientId=" + c.getId(), "/receipts/new?projectId=" + p.getId(),
                "/receipts/new?accountId=" + a.getId() + "&type=ACCOUNT_OPENING", "/receipts/" + receiptId, "/expenses",
                "/reports/daily", "/reports/daily?all=true&from=" + d.minusMonths(3) + "&to=" + d, "/reports/monthly",
                "/reports/monthly?month=" + java.time.YearMonth.from(d.minusMonths(2)),
                "/register", "/register?view=inactive", "/register?tab=deposits", "/register?tab=projects", "/register?tab=projects&view=due",
                "/register?tab=loans", "/register?tab=membership", "/register?tab=subs", "/import", "/branches"
        };
        for (String page : pages) {
            mvc.perform(get(page)).andExpect(status().isOk());
        }
        // date inputs need ISO values or browsers show them blank and refuse to submit
        mvc.perform(get("/clients/new")).andExpect(content().string(Matchers.containsString("value=\"" + d + "\"")));
        mvc.perform(get("/reports/register.xlsx")).andExpect(status().isOk());
        mvc.perform(get("/reports/monthly.xlsx")).andExpect(status().isOk());
        mvc.perform(get("/import/template?branchId=" + a.getBranch().getId())).andExpect(status().isOk());

        // receipt the opening fee balance by typing the account number
        mvc.perform(post("/receipts/new").with(csrf()).param("lookup", a.getAccountNo()).param("type", "ACCOUNT_OPENING")
                        .param("amount", "20").param("receiptDate", d.toString()).param("paymentMethod", "Cash"))
                .andExpect(status().is3xxRedirection());
        // a validation error re-renders the form with the message
        mvc.perform(post("/receipts/new").with(csrf()).param("lookup", "nobody").param("type", "SUBSCRIPTION")
                        .param("amount", "2").param("receiptDate", d.toString()))
                .andExpect(status().isOk()).andExpect(content().string(Matchers.containsString("No client or account")));
        // register a member through the form
        mvc.perform(post("/clients/new").with(csrf()).param("branchId", a.getBranch().getId().toString())
                        .param("firstName", "Mary").param("surname", "Mamhunze").param("dateRegistered", d.toString())
                        .param("category", "WIDOW").param("joinSacco", "true").param("membership.payJoiningFee", "true")
                        .param("membership.subsMonths", "1").param("paymentMethod", "Cash"))
                .andExpect(status().is3xxRedirection());
    }
}
