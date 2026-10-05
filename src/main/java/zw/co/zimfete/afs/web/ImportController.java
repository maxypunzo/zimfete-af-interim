package zw.co.zimfete.afs.web;

import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import zw.co.zimfete.afs.repo.BranchRepository;
import zw.co.zimfete.afs.service.BusinessException;
import zw.co.zimfete.afs.service.ExcelExportService;
import zw.co.zimfete.afs.service.ExcelImportService;

/** District returns: download the blank template for a clerk, upload what they send back on WhatsApp. */
@Controller
@RequestMapping("/import")
public class ImportController {
    private final ExcelImportService importer;
    private final ExcelExportService excel;
    private final BranchRepository branches;

    public ImportController(ExcelImportService importer, ExcelExportService excel, BranchRepository branches) {
        this.importer = importer;
        this.excel = excel;
        this.branches = branches;
    }

    @GetMapping
    public String page() {
        return "import";
    }

    @PostMapping
    public String upload(@RequestParam Long branchId, @RequestParam("file") MultipartFile file, Model model) {
        model.addAttribute("branchId", branchId);
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
