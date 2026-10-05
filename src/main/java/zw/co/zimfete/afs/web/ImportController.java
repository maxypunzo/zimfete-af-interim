package zw.co.zimfete.afs.web;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import zw.co.zimfete.afs.repo.BranchRepository;
import zw.co.zimfete.afs.service.BusinessException;
import zw.co.zimfete.afs.service.ExcelExportService;
import zw.co.zimfete.afs.service.ExcelImportService;
import zw.co.zimfete.afs.service.OldRegisterMigrationService;

/** District returns: download the blank template for a clerk, upload what they send back on WhatsApp. */
@Controller
@RequestMapping("/import")
public class ImportController {
    private static final String REPORT = "oldRegisterReport";

    private final ExcelImportService importer;
    private final ExcelExportService excel;
    private final BranchRepository branches;
    private final OldRegisterMigrationService migration;

    public ImportController(ExcelImportService importer, ExcelExportService excel, BranchRepository branches,
                            OldRegisterMigrationService migration) {
        this.importer = importer;
        this.excel = excel;
        this.branches = branches;
        this.migration = migration;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("oldImported", migration.alreadyImported());
        return "import";
    }

    /** One-off: the previous AFM's workbook. */
    @PostMapping("/old-register")
    public String oldRegister(@RequestParam("file") MultipartFile file,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt,
                              Model model, HttpSession session) {
        try {
            OldRegisterMigrationService.Result r = migration.migrate(file.getInputStream(), asAt);
            session.setAttribute(REPORT, migration.reportWorkbook(r));
            model.addAttribute("r", r);
            return "import-old-result";
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
        } catch (Exception e) {
            model.addAttribute("error", "Could not import " + file.getOriginalFilename() + ": " + e.getMessage());
        }
        model.addAttribute("oldImported", migration.alreadyImported());
        return "import";
    }

    @GetMapping("/old-register/report.xlsx")
    public ResponseEntity<byte[]> oldRegisterReport(HttpSession session) {
        byte[] data = (byte[]) session.getAttribute(REPORT);
        if (data == null) return ResponseEntity.notFound().build();
        return ReportController.file(data, "Old-register-import-to-confirm.xlsx");
    }

    @PostMapping
    public String upload(@RequestParam Long branchId, @RequestParam("file") MultipartFile file, Model model) {
        model.addAttribute("branchId", branchId);
        model.addAttribute("oldImported", migration.alreadyImported());
        if (file.isEmpty()) {
            model.addAttribute("error", "Choose the Excel file the clerk sent.");
            return "import";
        }
        try {
            model.addAttribute("result", importer.importReturn(file.getInputStream(), branchId));
            model.addAttribute("fileName", file.getOriginalFilename());
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
        } catch (Exception e) {
            model.addAttribute("error", "Could not read " + file.getOriginalFilename() + " as an Excel file: " + e.getMessage());
        }
        return "import";
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam Long branchId) throws IOException {
        String code = branches.findById(branchId).orElseThrow().getCode();
        return ReportController.file(excel.districtReturnTemplate(branchId), "ZimFete-AF-Return-" + code + ".xlsx");
    }
}
