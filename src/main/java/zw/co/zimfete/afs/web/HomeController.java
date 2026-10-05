package zw.co.zimfete.afs.web;

import java.time.LocalDate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import zw.co.zimfete.afs.domain.ProjectStatus;
import zw.co.zimfete.afs.repo.ReceiptRepository;
import zw.co.zimfete.afs.service.ReportService;

@Controller
public class HomeController {
    private final ReportService reports;
    private final ReceiptRepository receipts;

    public HomeController(ReportService reports, ReceiptRepository receipts) {
        this.reports = reports;
        this.receipts = receipts;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        LocalDate today = LocalDate.now();
        var stats = reports.districtStats(today);
        model.addAttribute("stats", stats);
        model.addAttribute("totals", ReportService.DistrictStats.total(stats));
        model.addAttribute("awaiting", reports.byStatus(ProjectStatus.THRESHOLD_MET, null));
        model.addAttribute("approved", reports.byStatus(ProjectStatus.APPROVED, null));
        model.addAttribute("due", reports.dueProjects(today, 14, null));
        model.addAttribute("todayReport", reports.cashReport(today, today, null));
        model.addAttribute("recent", receipts.find(today.minusDays(7), today, null, null).stream().limit(10).toList());
        return "dashboard";
    }
}
