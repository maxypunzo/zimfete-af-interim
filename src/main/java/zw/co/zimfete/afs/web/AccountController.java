package zw.co.zimfete.afs.web;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import zw.co.zimfete.afs.domain.AssetAccount;
import zw.co.zimfete.afs.domain.LoanTerms;
import zw.co.zimfete.afs.repo.ReceiptRepository;
import zw.co.zimfete.afs.service.AccountRequest;
import zw.co.zimfete.afs.service.AccountService;
import zw.co.zimfete.afs.service.BusinessException;

@Controller
@RequestMapping("/accounts")
public class AccountController {
    private final AccountService accountService;
    private final ReceiptRepository receipts;

    public AccountController(AccountService accountService, ReceiptRepository receipts) {
        this.accountService = accountService;
        this.receipts = receipts;
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, @RequestParam(required = false) Integer months, Model model) {
        AssetAccount a = accountService.get(id);
        model.addAttribute("a", a);
        model.addAttribute("receipts", receipts.findByAccountIdOrderByReceiptDateAscIdAsc(id));
        Integer m = months != null ? months : a.getRepaymentMonths() != null ? a.getRepaymentMonths() : 12;
        model.addAttribute("months", m);
        if (!a.isLoanStarted() && a.getQuotationCost() != null) {
            model.addAttribute("projection", LoanTerms.calculate(a.getQuotationCost(), a.getTotalDeposited(), m));
        }
        model.addAttribute("arrears", a.getArrears(LocalDate.now()));
        return "accounts/view";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        AssetAccount a = accountService.get(id);
        AccountRequest f = new AccountRequest();
        f.setOpenedBy(a.getOpenedBy());
        f.setAssetType(a.getAssetType());
        f.setAssetDescription(a.getAssetDescription());
        f.setSupplier(a.getSupplier());
        f.setQuotationCost(a.getQuotationCost());
        f.setMinDepositPercent(a.getMinDepositPercent());
        f.setRepaymentMonths(a.getRepaymentMonths());
        f.setTargetDate(a.getTargetDate());
        f.setNotes(a.getNotes());
        model.addAttribute("a", a);
        model.addAttribute("form", f);
        return "accounts/edit";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id, @ModelAttribute("form") AccountRequest form, Model model, RedirectAttributes ra) {
        try {
            accountService.updateDetails(id, form);
            ra.addFlashAttribute("message", "Project details saved.");
            return "redirect:/accounts/" + id;
        } catch (BusinessException e) {
            model.addAttribute("a", accountService.get(id));
            model.addAttribute("error", e.getMessage());
            return "accounts/edit";
        }
    }

    @PostMapping("/{id}/start")
    public String start(@PathVariable Long id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                        @RequestParam Integer months, RedirectAttributes ra) {
        return act(id, ra, () -> {
            AssetAccount a = accountService.startProject(id, startDate, months);
            return "Project started. Loan $" + a.getLoanTerms().totalRepayable() + " over " + months + " months ($"
                    + a.getLoanTerms().monthlyInstalment() + "/month).";
        });
    }

    @PostMapping("/{id}/complete")
    public String complete(@PathVariable Long id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate completionDate,
                           RedirectAttributes ra) {
        return act(id, ra, () -> {
            accountService.complete(id, completionDate);
            return "Project marked completed.";
        });
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(id, ra, () -> {
            accountService.cancel(id, reason);
            return "Account cancelled.";
        });
    }

    private String act(Long id, RedirectAttributes ra, java.util.function.Supplier<String> action) {
        try {
            ra.addFlashAttribute("message", action.get());
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/accounts/" + id;
    }
}
