package zw.co.zimfete.afs.web;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import zw.co.zimfete.afs.config.AfsProperties;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.*;
import zw.co.zimfete.afs.service.*;

@Controller
@RequestMapping("/receipts")
public class ReceiptController {
    private final ReceiptRepository receipts;
    private final ClientRepository clients;
    private final AssetAccountRepository accounts;
    private final ProjectRepository projects;
    private final ReceiptService receiptService;
    private final AfsProperties props;

    public ReceiptController(ReceiptRepository receipts, ClientRepository clients, AssetAccountRepository accounts,
                             ProjectRepository projects, ReceiptService receiptService, AfsProperties props) {
        this.receipts = receipts;
        this.clients = clients;
        this.accounts = accounts;
        this.projects = projects;
        this.receiptService = receiptService;
        this.props = props;
    }

    @GetMapping
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) Long branchId, @RequestParam(required = false) ReceiptType type, Model model) {
        LocalDate t = to != null ? to : LocalDate.now();
        LocalDate f = from != null ? from : t.withDayOfMonth(1);
        List<Receipt> list = receipts.find(f, t, branchId, type);
        model.addAttribute("receipts", list);
        model.addAttribute("total", ReportService.sum(list.stream().filter(r -> !r.isReversed()).map(Receipt::getAmount)));
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("branchId", branchId);
        model.addAttribute("type", type);
        model.addAttribute("types", ReceiptType.values());
        return "receipts/list";
    }

    @GetMapping("/new")
    public String newForm(@RequestParam(required = false) Long clientId, @RequestParam(required = false) Long accountId,
                          @RequestParam(required = false) Long projectId, @RequestParam(required = false) ReceiptType type, Model model) {
        ReceiptRequest form = new ReceiptRequest();
        form.setClientId(clientId);
        form.setAccountId(accountId);
        form.setProjectId(projectId);
        form.setType(type);
        form.setCapturedBy(props.officerName());
        if (type != null && type.getStandardAmount() != null) form.setAmount(type.getStandardAmount());
        if (projectId != null) {
            Project p = projects.findById(projectId).orElseThrow();
            form.setClientId(p.getClient().getId());
            form.setAccountId(p.getAccount().getId());
            form.setBranchId(p.getBranch().getId());
            if (type == null) form.setType(p.isLoanStarted() ? ReceiptType.LOAN_REPAYMENT : ReceiptType.ASSET_DEPOSIT);
            if (p.isLoanStarted() && form.getAmount() == null) form.setAmount(p.getLoanTerms().monthlyInstalment());
        } else if (accountId != null) {
            AssetAccount a = accounts.findById(accountId).orElseThrow();
            form.setClientId(a.getClient().getId());
            form.setBranchId(a.getBranch().getId());
            if (type == ReceiptType.ACCOUNT_OPENING) form.setAmount(a.getOpeningFeeBalance());
        } else if (clientId != null) {
            form.setBranchId(clients.findById(clientId).orElseThrow().getBranch().getId());
        }
        return show(form, null, model);
    }

    @PostMapping("/new")
    public String record(@ModelAttribute("form") ReceiptRequest form, @RequestParam(required = false) String lookup,
                         Model model, RedirectAttributes ra) {
        try {
            resolveLookup(form, lookup);
            Receipt r = receiptService.record(form);
            ra.addFlashAttribute("message", "Receipt " + r.getReceiptNo() + " saved: " + r.getType().getLabel() + " $" + r.getAmount()
                    + (r.getClient() != null ? " from " + r.getClient().getFullName() : "") + ".");
            return "redirect:/receipts/" + r.getId();
        } catch (BusinessException e) {
            return show(form, e.getMessage(), model);
        }
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        model.addAttribute("r", receipts.findById(id).orElseThrow());
        return "receipts/print";
    }

    @PostMapping("/{id}/reverse")
    public String reverse(@PathVariable Long id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        try {
            receiptService.reverse(id, reason);
            ra.addFlashAttribute("message", "Receipt reversed. Totals and registers recalculated.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/receipts/" + id;
    }

    private String show(ReceiptRequest form, String error, Model model) {
        model.addAttribute("form", form);
        model.addAttribute("error", error);
        model.addAttribute("types", Arrays.asList(ReceiptType.values()));
        if (form.getClientId() != null) {
            Client c = clients.findById(form.getClientId()).orElse(null);
            model.addAttribute("client", c);
            if (c != null) {
                model.addAttribute("clientAccounts", accounts.findByClientIdOrderByOpenedDateDesc(c.getId()).stream()
                        .filter(a -> !a.isClosed()).toList());
                model.addAttribute("clientProjects", projects.findByClient(c.getId()).stream()
                        .filter(p -> p.getStatus() != ProjectStatus.CANCELLED && p.getStatus() != ProjectStatus.COMPLETED || p.isLoanStarted()).toList());
            }
        }
        return "receipts/new";
    }

    /** Lets the clerk type a client no, national ID or account no instead of searching first. */
    private void resolveLookup(ReceiptRequest form, String lookup) {
        if (form.getClientId() != null || form.getAccountId() != null || form.getProjectId() != null
                || lookup == null || lookup.isBlank()) return;
        String key = lookup.trim();
        var acc = accounts.findByAccountNoIgnoreCase(key);
        if (acc.isPresent()) {
            form.setAccountId(acc.get().getId());
            return;
        }
        Client c = clients.findByNationalIdIgnoreCase(ClientService.normaliseId(key))
                .or(() -> clients.findByClientNoIgnoreCase(key))
                .orElseThrow(() -> new BusinessException("No client or account matches '" + key + "'."));
        form.setClientId(c.getId());
        if (form.getType() != null && (form.getType().needsProject() || form.getType() == ReceiptType.ACCOUNT_OPENING)) {
            List<AssetAccount> open = accounts.findByClientIdOrderByOpenedDateDesc(c.getId()).stream().filter(a -> !a.isClosed()).toList();
            if (open.size() == 1) form.setAccountId(open.get(0).getId());
        }
    }
}
