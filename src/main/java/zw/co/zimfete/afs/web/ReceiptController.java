package zw.co.zimfete.afs.web;

import java.time.LocalDate;
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
    private final MemberRepository members;
    private final AssetAccountRepository accounts;
    private final ReceiptService receiptService;
    private final AfsProperties props;

    public ReceiptController(ReceiptRepository receipts, MemberRepository members, AssetAccountRepository accounts,
                             ReceiptService receiptService, AfsProperties props) {
        this.receipts = receipts;
        this.members = members;
        this.accounts = accounts;
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
    public String newForm(@RequestParam(required = false) Long memberId, @RequestParam(required = false) Long accountId,
                          @RequestParam(required = false) ReceiptType type, Model model) {
        ReceiptRequest form = new ReceiptRequest();
        form.setMemberId(memberId);
        form.setAccountId(accountId);
        form.setType(type);
        form.setCapturedBy(props.officerName());
        if (type != null && type.getStandardAmount() != null) form.setAmount(type.getStandardAmount());
        if (accountId != null) {
            AssetAccount a = accounts.findById(accountId).orElseThrow();
            form.setMemberId(a.getMember().getId());
            form.setBranchId(a.getBranch().getId());
            if (type == null) form.setType(a.isLoanStarted() ? ReceiptType.LOAN_REPAYMENT : ReceiptType.ASSET_DEPOSIT);
            if (a.isLoanStarted() && form.getAmount() == null) form.setAmount(a.getLoanTerms().monthlyInstalment());
        } else if (memberId != null) {
            form.setBranchId(members.findById(memberId).orElseThrow().getBranch().getId());
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
                    + (r.getMember() != null ? " from " + r.getMember().getFullName() : "") + ".");
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
        model.addAttribute("types", java.util.Arrays.stream(ReceiptType.values()).filter(t -> t != ReceiptType.ACCOUNT_OPENING).toList());
        if (form.getMemberId() != null) {
            Member m = members.findById(form.getMemberId()).orElse(null);
            model.addAttribute("member", m);
            if (m != null) model.addAttribute("memberAccounts", accounts.findByMemberIdOrderByOpenedDateDesc(m.getId()).stream()
                    .filter(a -> a.getStatus() != ProjectStatus.CANCELLED).toList());
        }
        return "receipts/new";
    }

    /** Lets the clerk type a member no, national ID or account no instead of searching first. */
    private void resolveLookup(ReceiptRequest form, String lookup) {
        if (form.getMemberId() != null || form.getAccountId() != null || lookup == null || lookup.isBlank()) return;
        String key = lookup.trim();
        var acc = accounts.findByAccountNoIgnoreCase(key);
        if (acc.isPresent()) {
            form.setAccountId(acc.get().getId());
            return;
        }
        Member m = members.findByNationalIdIgnoreCase(MemberService.normaliseId(key))
                .or(() -> members.search(null, key).stream().filter(x -> x.getMemberNo().equalsIgnoreCase(key)).findFirst())
                .orElseThrow(() -> new BusinessException("No member or account matches '" + key + "'."));
        form.setMemberId(m.getId());
        if (form.getType() != null && form.getType().needsAccount()) {
            List<AssetAccount> open = accounts.findByMemberIdOrderByOpenedDateDesc(m.getId()).stream()
                    .filter(a -> a.getStatus() != ProjectStatus.CANCELLED).toList();
            if (open.size() == 1) form.setAccountId(open.get(0).getId());
        }
    }
}
