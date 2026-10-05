package zw.co.zimfete.afs.web;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.MemberRepository;
import zw.co.zimfete.afs.repo.ReceiptRepository;
import zw.co.zimfete.afs.service.ReportService;

/** The AFM master asset finance register: accounts, deposits, projects, loan book, subscriptions. */
@Controller
public class RegisterController {
    private final ReportService reports;
    private final ReceiptRepository receipts;
    private final MemberRepository members;

    public RegisterController(ReportService reports, ReceiptRepository receipts, MemberRepository members) {
        this.reports = reports;
        this.receipts = receipts;
        this.members = members;
    }

    @GetMapping("/register")
    public String register(@RequestParam(defaultValue = "accounts") String tab,
                           @RequestParam(required = false) Long branchId,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(required = false) ProjectStatus status,
                           @RequestParam(required = false) AssetType assetType,
                           @RequestParam(required = false) String view,
                           Model model) {
        LocalDate today = LocalDate.now();
        LocalDate f = from != null ? from : LocalDate.of(2000, 1, 1);
        LocalDate t = to != null ? to : today;
        model.addAttribute("tab", tab);
        model.addAttribute("branchId", branchId);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("status", status);
        model.addAttribute("assetType", assetType);
        model.addAttribute("view", view);

        switch (tab) {
            case "deposits" -> {
                List<Receipt> list = receipts.find(f, t, branchId, ReceiptType.ASSET_DEPOSIT).stream()
                        .filter(r -> !r.isReversed())
                        .filter(r -> assetType == null || r.getAccount().getAssetType() == assetType).toList();
                model.addAttribute("deposits", list);
                model.addAttribute("total", ReportService.sum(list.stream().map(Receipt::getAmount)));
            }
            case "projects" -> {
                List<AssetAccount> list;
                if ("due".equals(view)) {
                    list = reports.dueProjects(today, 14, branchId);
                } else {
                    list = reports.byStatus(status, branchId).stream()
                            .filter(a -> a.getStatus() != ProjectStatus.CANCELLED || status == ProjectStatus.CANCELLED).toList();
                }
                model.addAttribute("projects", list);
            }
            case "loans" -> {
                List<AssetAccount> list = reports.byStatus(null, branchId).stream().filter(AssetAccount::isLoanStarted)
                        .sorted(Comparator.comparing((AssetAccount a) -> a.getArrears(today)).reversed()).toList();
                model.addAttribute("loans", list);
                model.addAttribute("book", ReportService.sum(list.stream().map(AssetAccount::getLoanBalance)));
                model.addAttribute("arrears", ReportService.sum(list.stream().map(a -> a.getArrears(today))));
            }
            case "subs" -> model.addAttribute("arrearsMembers", members.search(branchId, null).stream()
                    .filter(m -> m.subsMonthsOwed(today) > 0)
                    .sorted(Comparator.comparing((Member m) -> m.subsMonthsOwed(today)).reversed()).toList());
            default -> {
                List<AssetAccount> list = reports.byStatus(status, branchId).stream()
                        .filter(a -> !a.getOpenedDate().isBefore(f) && !a.getOpenedDate().isAfter(t)).toList();
                model.addAttribute("accounts", list);
                model.addAttribute("byClerk", reports.accountsOpenedBy(list));
                model.addAttribute("byBranch", list.stream().collect(java.util.stream.Collectors.groupingBy(
                        a -> a.getBranch().getLabel(), java.util.TreeMap::new, java.util.stream.Collectors.counting())));
            }
        }
        return "register";
    }
}
