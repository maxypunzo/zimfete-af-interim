package zw.co.zimfete.afs.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import zw.co.zimfete.afs.config.AfsProperties;
import zw.co.zimfete.afs.domain.AssetAccount;
import zw.co.zimfete.afs.domain.Project;
import zw.co.zimfete.afs.repo.ReceiptRepository;
import zw.co.zimfete.afs.service.*;

@Controller
@RequestMapping("/accounts")
public class AccountController {
    private final AccountService accountService;
    private final ProjectService projectService;
    private final ReceiptRepository receipts;
    private final AfsProperties props;

    public AccountController(AccountService accountService, ProjectService projectService, ReceiptRepository receipts,
                             AfsProperties props) {
        this.accountService = accountService;
        this.projectService = projectService;
        this.receipts = receipts;
        this.props = props;
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        AssetAccount a = accountService.get(id);
        model.addAttribute("a", a);
        model.addAttribute("projects", projectService.forAccount(id));
        model.addAttribute("receipts", receipts.findByAccountIdOrderByReceiptDateAscIdAsc(id));
        return "accounts/view";
    }

    @GetMapping("/{id}/projects/new")
    public String newProjectForm(@PathVariable Long id, Model model) {
        ProjectRequest form = new ProjectRequest();
        form.setCapturedBy(props.officerName());
        model.addAttribute("a", accountService.get(id));
        model.addAttribute("form", form);
        return "projects/new";
    }

    @PostMapping("/{id}/projects/new")
    public String addProject(@PathVariable Long id, @ModelAttribute("form") ProjectRequest form, Model model, RedirectAttributes ra) {
        try {
            Project p = projectService.create(id, form, "MANUAL");
            ra.addFlashAttribute("message", "Project " + p.getAssetLabel() + " added to " + p.getAccount().getAccountNo() + ".");
            return "redirect:/projects/" + p.getId();
        } catch (BusinessException e) {
            model.addAttribute("a", accountService.get(id));
            model.addAttribute("error", e.getMessage());
            return "projects/new";
        }
    }

    @PostMapping("/{id}/close")
    public String close(@PathVariable Long id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        try {
            accountService.close(id, reason);
            ra.addFlashAttribute("message", "Account closed.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/accounts/" + id;
    }
}
