package zw.co.zimfete.afs.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Supplier;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import zw.co.zimfete.afs.domain.LoanTerms;
import zw.co.zimfete.afs.domain.Project;
import zw.co.zimfete.afs.repo.ReceiptRepository;
import zw.co.zimfete.afs.service.BusinessException;
import zw.co.zimfete.afs.service.ProjectRequest;
import zw.co.zimfete.afs.service.ProjectService;

@Controller
@RequestMapping("/projects")
public class ProjectController {
    private final ProjectService projectService;
    private final ReceiptRepository receipts;

    public ProjectController(ProjectService projectService, ReceiptRepository receipts) {
        this.projectService = projectService;
        this.receipts = receipts;
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, @RequestParam(required = false) Integer months,
                       @RequestParam(required = false) BigDecimal rate, Model model) {
        Project p = projectService.get(id);
        model.addAttribute("p", p);
        model.addAttribute("receipts", receipts.findByProjectIdOrderByReceiptDateAscIdAsc(id));
        Integer m = months != null ? months : p.getRepaymentMonths() != null ? p.getRepaymentMonths() : 12;
        BigDecimal r = rate != null ? rate : p.getInterestRate();
        model.addAttribute("months", m);
        model.addAttribute("rate", r);
        if (!p.isLoanStarted() && p.getQuotationCost() != null) {
            model.addAttribute("projection", LoanTerms.calculate(p.getQuotationCost(), p.getTotalDeposited(), m, r));
        }
        model.addAttribute("arrears", p.getArrears(LocalDate.now()));
        return "projects/view";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        Project p = projectService.get(id);
        ProjectRequest f = new ProjectRequest();
        f.setAssetType(p.getAssetType());
        f.setAssetDescription(p.getAssetDescription());
        f.setSupplier(p.getSupplier());
        f.setQuotationCost(p.getQuotationCost());
        f.setMinDepositPercent(p.getMinDepositPercent());
        f.setInterestPercent(p.getInterestPercent());
        f.setRepaymentMonths(p.getRepaymentMonths());
        f.setTargetDate(p.getTargetDate());
        f.setNotes(p.getNotes());
        model.addAttribute("p", p);
        model.addAttribute("form", f);
        return "projects/edit";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id, @ModelAttribute("form") ProjectRequest form, Model model, RedirectAttributes ra) {
        try {
            projectService.update(id, form);
            ra.addFlashAttribute("message", "Project details saved.");
            return "redirect:/projects/" + id;
        } catch (BusinessException e) {
            model.addAttribute("p", projectService.get(id));
            model.addAttribute("error", e.getMessage());
            return "projects/edit";
        }
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate approvedDate,
                          @RequestParam(required = false) String note, RedirectAttributes ra) {
        return act(id, ra, () -> {
            projectService.approve(id, approvedDate, note);
            return "Committee approval recorded.";
        });
    }

    @PostMapping("/{id}/start")
    public String start(@PathVariable Long id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                        @RequestParam Integer months, @RequestParam(required = false) BigDecimal rate,
                        @RequestParam(required = false) BigDecimal disbursedAmount, @RequestParam(required = false) String disbursedTo,
                        RedirectAttributes ra) {
        return act(id, ra, () -> {
            Project p = projectService.start(id, startDate, months, rate, disbursedAmount, disbursedTo);
            return "Funds disbursed and loan fixed: $" + p.getLoanTerms().totalRepayable() + " over " + months + " months ($"
                    + p.getLoanTerms().monthlyInstalment() + "/month).";
        });
    }

    @PostMapping("/{id}/complete")
    public String complete(@PathVariable Long id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate completionDate,
                           RedirectAttributes ra) {
        return act(id, ra, () -> {
            projectService.complete(id, completionDate);
            return "Project marked completed.";
        });
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(id, ra, () -> {
            projectService.cancel(id, reason);
            return "Project cancelled.";
        });
    }

    private String act(Long id, RedirectAttributes ra, Supplier<String> action) {
        try {
            ra.addFlashAttribute("message", action.get());
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/projects/" + id;
    }
}
