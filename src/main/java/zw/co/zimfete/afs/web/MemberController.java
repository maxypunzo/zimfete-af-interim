package zw.co.zimfete.afs.web;

import java.time.LocalDate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import zw.co.zimfete.afs.config.AfsProperties;
import zw.co.zimfete.afs.domain.AssetAccount;
import zw.co.zimfete.afs.domain.Member;
import zw.co.zimfete.afs.repo.AssetAccountRepository;
import zw.co.zimfete.afs.repo.BranchRepository;
import zw.co.zimfete.afs.repo.MemberRepository;
import zw.co.zimfete.afs.repo.ReceiptRepository;
import zw.co.zimfete.afs.service.*;

@Controller
@RequestMapping("/members")
public class MemberController {
    private final MemberRepository members;
    private final AssetAccountRepository accounts;
    private final ReceiptRepository receipts;
    private final BranchRepository branches;
    private final MemberService memberService;
    private final AccountService accountService;
    private final AfsProperties props;

    public MemberController(MemberRepository members, AssetAccountRepository accounts, ReceiptRepository receipts,
                            BranchRepository branches, MemberService memberService, AccountService accountService,
                            AfsProperties props) {
        this.members = members;
        this.accounts = accounts;
        this.receipts = receipts;
        this.branches = branches;
        this.memberService = memberService;
        this.accountService = accountService;
        this.props = props;
    }

    @GetMapping
    public String list(@RequestParam(required = false) Long branchId, @RequestParam(required = false) String q, Model model) {
        String query = q == null || q.isBlank() ? null : q.trim();
        model.addAttribute("members", members.search(branchId, query));
        model.addAttribute("branchId", branchId);
        model.addAttribute("q", query);
        return "members/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        RegistrationRequest form = new RegistrationRequest();
        form.setCapturedBy(props.officerName());
        branches.findFirstByHeadOfficeTrue().ifPresent(b -> form.setBranchId(b.getId()));
        model.addAttribute("form", form);
        return "members/new";
    }

    @PostMapping("/new")
    public String register(@ModelAttribute("form") RegistrationRequest form, Model model, RedirectAttributes ra) {
        try {
            Member m = memberService.register(form, "MANUAL");
            ra.addFlashAttribute("message", "Registered " + m.getFullName() + " as " + m.getMemberNo() + ". Receipts and registers updated.");
            return "redirect:/members/" + m.getId();
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            return "members/new";
        }
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        Member m = members.findById(id).orElseThrow();
        model.addAttribute("member", m);
        model.addAttribute("accounts", accounts.findByMemberIdOrderByOpenedDateDesc(id));
        model.addAttribute("receipts", receipts.findByMemberIdOrderByReceiptDateDescIdDesc(id));
        model.addAttribute("monthsOwed", m.subsMonthsOwed(LocalDate.now()));
        return "members/view";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        model.addAttribute("member", members.findById(id).orElseThrow());
        model.addAttribute("memberId", id);
        return "members/edit";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id, @ModelAttribute("member") Member form, Model model, RedirectAttributes ra) {
        try {
            memberService.update(id, form);
            ra.addFlashAttribute("message", "Member details saved.");
            return "redirect:/members/" + id;
        } catch (BusinessException e) {
            model.addAttribute("memberId", id);
            model.addAttribute("error", e.getMessage());
            return "members/edit";
        }
    }

    @GetMapping("/{id}/open-account")
    public String openAccountForm(@PathVariable Long id, Model model) {
        AccountRequest form = new AccountRequest();
        form.setOpenedBy(props.officerName());
        model.addAttribute("member", members.findById(id).orElseThrow());
        model.addAttribute("form", form);
        return "members/open-account";
    }

    @PostMapping("/{id}/open-account")
    public String openAccount(@PathVariable Long id, @ModelAttribute("form") AccountRequest form, Model model, RedirectAttributes ra) {
        try {
            AssetAccount a = accountService.open(id, form, "MANUAL");
            ra.addFlashAttribute("message", "Account " + a.getAccountNo() + " opened and $50 opening fee receipted.");
            return "redirect:/accounts/" + a.getId();
        } catch (BusinessException e) {
            model.addAttribute("member", members.findById(id).orElseThrow());
            model.addAttribute("error", e.getMessage());
            return "members/open-account";
        }
    }
}
