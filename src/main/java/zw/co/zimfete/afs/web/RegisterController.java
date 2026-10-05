package zw.co.zimfete.afs.web;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.AssetAccountRepository;
import zw.co.zimfete.afs.repo.ClientRepository;
import zw.co.zimfete.afs.repo.ReceiptRepository;
import zw.co.zimfete.afs.service.ReportService;

/** The AFM master register: accounts, deposits, projects, loan book, membership, subscriptions. */
@Controller
public class RegisterController {
    private final ReportService reports;
    private final ReceiptRepository receipts;
    private final ClientRepository clients;
    private final AssetAccountRepository accounts;

    public RegisterController(ReportService reports, ReceiptRepository receipts, ClientRepository clients,
                              AssetAccountRepository accounts) {
        this.reports = reports;
        this.receipts = receipts;
        this.clients = clients;
        this.accounts = accounts;
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
                // old cash-book lines are not linked to projects; their amounts are in the balances brought forward
                List<Receipt> list = receipts.find(f, t, branchId, ReceiptType.ASSET_DEPOSIT).stream()
                        .filter(r -> !r.isReversed() && r.getProject() != null)
                        .filter(r -> assetType == null || r.getProject().getAssetType() == assetType).toList();
                model.addAttribute("deposits", list);
                model.addAttribute("total", ReportService.sum(list.stream().map(Receipt::getAmount)));
            }
            case "projects" -> {
                List<Project> list = "due".equals(view) ? reports.dueProjects(today, 14, branchId)
                        : reports.byStatus(status, branchId).stream()
                        .filter(p -> p.getStatus() != ProjectStatus.CANCELLED || status == ProjectStatus.CANCELLED).toList();
                model.addAttribute("projects", list);
            }
            case "loans" -> {
                List<Project> list = reports.byStatus(null, branchId).stream().filter(Project::isLoanStarted)
                        .sorted(Comparator.comparing((Project p) -> p.getArrears(today)).reversed()).toList();
                model.addAttribute("loans", list);
                model.addAttribute("book", ReportService.sum(list.stream().map(Project::getLoanBalance)));
                model.addAttribute("arrears", ReportService.sum(list.stream().map(p -> p.getArrears(today))));
            }
            case "membership" -> model.addAttribute("membership", reports.membership());
            case "subs" -> model.addAttribute("arrearsMembers", clients.search(branchId, null, true, null).stream()
                    .filter(c -> c.subsMonthsOwed(today) > 0)
                    .sorted(Comparator.comparing((Client c) -> c.subsMonthsOwed(today)).reversed()).toList());
            default -> {
                List<AssetAccount> list = accounts.findAllByOrderByOpenedDateDescIdDesc().stream()
                        .filter(a -> branchId == null || a.getBranch().getId().equals(branchId))
                        .filter(a -> a.getOpenedDate() == null ? from == null && to == null
                                : !a.getOpenedDate().isBefore(f) && !a.getOpenedDate().isAfter(t))
                        .filter(a -> !"inactive".equals(view) || !a.isActive()).toList();
                model.addAttribute("accounts", list);
                model.addAttribute("byClerk", reports.accountsOpenedBy(list));
                model.addAttribute("byBranch", list.stream().collect(Collectors.groupingBy(
                        a -> a.getBranch().getLabel(), TreeMap::new, Collectors.counting())));
            }
        }
        return "register";
    }
}
