package zw.co.zimfete.afs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.*;
import zw.co.zimfete.afs.service.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExcelImportTest {
    @Autowired ExcelExportService export;
    @Autowired ExcelImportService importer;
    @Autowired BranchRepository branches;
    @Autowired AssetAccountRepository accounts;
    @Autowired MemberRepository members;

    @Test
    void districtReturnRoundTripAndReimportIsSkipped() throws Exception {
        Branch mtk = branches.findByCode("MTK").orElseThrow();
        byte[] template = export.districtReturnTemplate(mtk.getId());

        LocalDate d = LocalDate.now().minusDays(1);
        String date = String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
        byte[] filled;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(template))) {
            Sheet s = wb.getSheet("Receipts");
            // Date, Receipt No, Type, First, Surname, ID, Phone, Gender, Village, Ward, Account No, Asset Type, Asset Desc,
            // Quotation, Target Date, Amount, Months, Method, Clerk, Notes
            row(s, 1, date, "1001", "JOINING_FEE", "Rudo", "Chari", "48-555555F66", "0771", "Female", "Nyamuzuwe", "5",
                    null, null, null, null, null, 10, null, "Cash", "Clerk MTK", null);
            row(s, 2, date, "1002", "ACCOUNT_OPENING", null, null, "48-555555F66", null, null, null, null,
                    "MTK/AF/015", "Solar", "3kW solar", 3000, null, 50, null, "Cash", "Clerk MTK", null);
            row(s, 3, date, "1003", "ASSET_DEPOSIT", null, null, "48555555F66", null, null, null, null,
                    "MTK/AF/015", null, null, null, null, 1600, null, "EcoCash", "Clerk MTK", null);
            row(s, 4, date, "1004", "SUBSCRIPTION", null, null, "48-555555F66", null, null, null, null,
                    null, null, null, null, null, 3, null, "Cash", "Clerk MTK", null);
            row(s, 5, date, "1005", "ASSET_DEPOSIT", null, null, "99-000000Z00", null, null, null, null,
                    null, null, null, null, null, 100, null, "Cash", "Clerk MTK", null);
            Sheet e = wb.getSheet("Expenditure");
            row(e, 1, date, "V1", "Transport", "Bus to HQ", "ZUPCO", 5, "Clerk MTK");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            filled = out.toByteArray();
        }

        ExcelImportService.ImportResult first = importer.importReturn(new ByteArrayInputStream(filled), mtk.getId());
        assertThat(first.getPosted()).as(first.rows().toString()).isEqualTo(5);
        assertThat(first.getErrors()).isEqualTo(1); // unknown ID on row 6

        Member m = members.findByNationalIdIgnoreCase("48555555F66").orElseThrow();
        assertThat(m.getBranch().getCode()).isEqualTo("MTK");
        assertThat(m.isJoiningFeePaid()).isTrue();
        AssetAccount a = accounts.findByAccountNoIgnoreCase("MTK/AF/015").orElseThrow();
        assertThat(a.getOpenedBy()).isEqualTo("Clerk MTK");
        assertThat(a.getAssetType()).isEqualTo(AssetType.SOLAR);
        assertThat(a.getTotalDeposited()).isEqualByComparingTo("1600");
        assertThat(a.getStatus()).isEqualTo(ProjectStatus.THRESHOLD_MET);

        ExcelImportService.ImportResult again = importer.importReturn(new ByteArrayInputStream(filled), mtk.getId());
        assertThat(again.getPosted()).isZero();
        assertThat(again.getSkipped()).isEqualTo(5);

        assertThat(export.masterRegister(LocalDate.now())).isNotEmpty();
    }

    @Test
    void templateHasTheExpectedSheets() throws Exception {
        byte[] t = export.districtReturnTemplate(branches.findByCode("HWZ").orElseThrow().getId());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(t))) {
            assertThat(wb.getSheet("Receipts")).isNotNull();
            assertThat(wb.getSheet("Expenditure")).isNotNull();
            assertThat(wb.getSheet("Accounts")).isNotNull();
        }
    }

    private static void row(Sheet s, int idx, Object... values) {
        Row r = s.createRow(idx);
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            if (v == null) continue;
            if (v instanceof Number n) r.createCell(i).setCellValue(n.doubleValue());
            else r.createCell(i).setCellValue(v.toString());
        }
    }
}
