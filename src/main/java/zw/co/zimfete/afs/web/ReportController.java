package zw.co.zimfete.afs.web;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import zw.co.zimfete.afs.domain.ReceiptType;
import zw.co.zimfete.afs.service.ExcelExportService;
import zw.co.zimfete.afs.service.ReportService;

@Controller
@RequestMapping("/reports")
public class ReportController {
    static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReportService reports;
    private final ExcelExportService excel;

    public ReportController(ReportService reports, ExcelExportService excel) {
        this.reports = reports;
        this.excel = excel;
    }

    /** Daily (or any date range) income & expenditure — what the clerk sends to the WhatsApp group. */
    @GetMapping("/daily")
    public String daily(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(required = false) Long branchId, @RequestParam(required = false) Boolean all, Model model) {
        LocalDate f = from != null ? from : LocalDate.now();
        LocalDate t = to != null ? to : f;
        Long b = Boolean.TRUE.equals(all) ? null : branchId != null ? branchId : (Long) model.getAttribute("hqBranchId");
        ReportService.CashReport r = reports.cashReport(f, t, b);
        model.addAttribute("r", r);
        model.addAttribute("whatsapp", reports.whatsappText(r));
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("branchId", b);
        model.addAttribute("types", ReceiptType.values());
        return "reports/daily";
    }

    @GetMapping("/monthly")
    public String monthly(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                          @RequestParam(required = false) Long branchId, Model model) {
        YearMonth m = month != null ? month : YearMonth.now();
        model.addAttribute("m", reports.monthly(m, branchId));
        model.addAttribute("month", m);
        model.addAttribute("branchId", branchId);
        model.addAttribute("types", ReceiptType.values());
        return "reports/monthly";
    }

    @GetMapping("/monthly.xlsx")
    public ResponseEntity<byte[]> monthlyExcel(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                                               @RequestParam(required = false) Long branchId) throws IOException {
        YearMonth m = month != null ? month : YearMonth.now();
        return file(excel.monthly(m, branchId), "ZimFete-AF-IncomeExpenditure-" + m + ".xlsx");
    }

    @GetMapping("/register.xlsx")
    public ResponseEntity<byte[]> register() throws IOException {
        return file(excel.masterRegister(LocalDate.now()), "ZimFete-AF-Master-Register-" + LocalDate.now() + ".xlsx");
    }

    static ResponseEntity<byte[]> file(byte[] data, String name) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(XLSX).body(data);
    }
}
