package zw.co.zimfete.afs.web;

import java.time.LocalDate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import zw.co.zimfete.afs.config.AfsProperties;
import zw.co.zimfete.afs.domain.AssetAccount;
import zw.co.zimfete.afs.domain.Client;
import zw.co.zimfete.afs.domain.MemberCategory;
import zw.co.zimfete.afs.repo.*;
import zw.co.zimfete.afs.service.*;

@Controller
@RequestMapping("/clients")
public class ClientController {
    private final ClientRepository clients;
    private final AssetAccountRepository accounts;
    private final ProjectRepository projects;
    private final ReceiptRepository receipts;
    private final BranchRepository branches;
    private final ClientService clientService;
    private final AccountService accountService;
    private final AfsProperties props;

    public ClientController(ClientRepository clients, AssetAccountRepository accounts, ProjectRepository projects,
                            ReceiptRepository receipts, BranchRepository branches, ClientService clientService,
                            AccountService accountService, AfsProperties props) {
        this.clients = clients;
        this.accounts = accounts;
        this.projects = projects;
        this.receipts = receipts;
        this.branches = branches;
        this.clientService = clientService;
        this.accountService = accountService;
        this.props = props;
    }

    @GetMapping
    public String list(@RequestParam(required = false) Long branchId, @RequestParam(required = false) String q,
                       @RequestParam(required = false) Boolean members, @RequestParam(required = false) MemberCategory category,
                       Model model) {
        String query = q == null || q.isBlank() ? null : q.trim();
        model.addAttribute("clients", clients.search(branchId, query, members, category));
        model.addAttribute("branchId", branchId);
        model.addAttribute("q", query);
        model.addAttribute("members", members);
        model.addAttribute("category", category);
        return "clients/list";
    }

    @GetMapping("/new")
    public String newForm(@RequestParam(required = false) Boolean member, Model model) {
        RegistrationRequest form = new RegistrationRequest();
        form.setCapturedBy(props.officerName());
        form.setJoinSacco(Boolean.TRUE.equals(member));
        if (Boolean.TRUE.equals(member)) form.setCategory(null); // the clerk must pick the veteran category
        form.setOpenAccount(!Boolean.TRUE.equals(member));
        branches.findFirstByHeadOfficeTrue().ifPresent(b -> form.setBranchId(b.getId()));
        model.addAttribute("form", form);
        return "clients/new";
    }

    @PostMapping("/new")
    public String register(@ModelAttribute("form") RegistrationRequest form, Model model, RedirectAttributes ra) {
        try {
            Client c = clientService.register(form, "MANUAL");
            ra.addFlashAttribute("message", "Registered " + c.getFullName() + " as " + c.getClientNo()
                    + (c.isSaccoMember() ? " (SACCO member)" : "") + ". Receipts and registers updated.");
            return "redirect:/clients/" + c.getId();
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            return "clients/new";
        }
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        Client c = clientService.get(id);
        model.addAttribute("client", c);
        model.addAttribute("accounts", accounts.findByClientIdOrderByOpenedDateDesc(id));
        model.addAttribute("projects", projects.findByClient(id));
        model.addAttribute("receipts", receipts.findByClientIdOrderByReceiptDateDescIdDesc(id));
        model.addAttribute("monthsOwed", c.subsMonthsOwed(LocalDate.now()));
        return "clients/view";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        model.addAttribute("client", clientService.get(id));
        model.addAttribute("clientId", id);
        return "clients/edit";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id, @ModelAttribute("client") Client form, Model model, RedirectAttributes ra) {
        try {
            clientService.update(id, form);
            ra.addFlashAttribute("message", "Client details saved.");
            return "redirect:/clients/" + id;
        } catch (BusinessException e) {
            model.addAttribute("clientId", id);
            model.addAttribute("error", e.getMessage());
            return "clients/edit";
        }
    }

    @GetMapping("/{id}/enrol")
    public String enrolForm(@PathVariable Long id, Model model) {
        Client c = clientService.get(id);
        MembershipRequest form = new MembershipRequest();
        form.setCategory(c.getCategory().isVeteranCommunity() ? c.getCategory() : null);
        form.setCapturedBy(props.officerName());
        model.addAttribute("client", c);
        model.addAttribute("form", form);
        return "clients/enrol";
    }

    @PostMapping("/{id}/enrol")
    public String enrol(@PathVariable Long id, @ModelAttribute("form") MembershipRequest form, Model model, RedirectAttributes ra) {
        try {
            Client c = clientService.enrol(id, form, "MANUAL");
            ra.addFlashAttribute("message", c.getFullName() + " is now a SACCO member (" + c.getCategory().getLabel() + ").");
            return "redirect:/clients/" + id;
        } catch (BusinessException e) {
            model.addAttribute("client", clientService.get(id));
            model.addAttribute("error", e.getMessage());
            return "clients/enrol";
        }
    }

    @GetMapping("/{id}/open-account")
    public String openAccountForm(@PathVariable Long id, Model model) {
        AccountRequest form = new AccountRequest();
        form.setOpenedBy(props.officerName());
        model.addAttribute("client", clientService.get(id));
        model.addAttribute("form", form);
        model.addAttribute("project", new ProjectRequest());
        return "clients/open-account";
    }

    @PostMapping("/{id}/open-account")
    public String openAccount(@PathVariable Long id, @ModelAttribute("form") AccountRequest form,
                              @ModelAttribute("project") ProjectRequest project, Model model, RedirectAttributes ra) {
        try {
            project.setPaymentMethod(form.getPaymentMethod());
            project.setCapturedBy(form.getOpenedBy());
            AssetAccount a = accountService.open(id, form, project.isEmpty() ? null : project, "MANUAL");
            ra.addFlashAttribute("message", "Account " + a.getAccountNo() + " opened. " + a.getStatusLabel() + ".");
            return "redirect:/accounts/" + a.getId();
        } catch (BusinessException e) {
            model.addAttribute("client", clientService.get(id));
            model.addAttribute("error", e.getMessage());
            return "clients/open-account";
        }
    }
}
