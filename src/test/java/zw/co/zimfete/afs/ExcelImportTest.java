package zw.co.zimfete.afs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
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
    @Autowired ProjectRepository projects;
    @Autowired ClientRepository clients;

    @Test
    void districtReturnRoundTripAndReimportIsSkipped() throws Exception {
        Branch mda = branches.findByCode("MDA").orElseThrow();
        byte[] template = export.districtReturnTemplate(mda.getId());

        LocalDate d = LocalDate.now().minusDays(1);
        String date = String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
        byte[] filled;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(template))) {
            Sheet s = wb.getSheet("Receipts");
            // Date, Receipt No, Type, First, Surname, ID, Phone, Gender, Village, Ward, District/Location, Category, Account No,
            // Asset Type, Asset Desc, Quotation, Target Date, Amount, Months, Method, Clerk, Notes
            // a war veteran joins (no national ID in the old registers)
            row(s, 1, date, "4817", "JOINING_FEE", "Cyrilo", "Chizengwe", null, "0772903006", "Male", "Loquate", "8", "Harare",
                    "WV", null, null, null, null, null, 10, null, "Cash", "Clerk MDA", null);
            // a non-member opens an account, paying the fee in two parts, then deposits for two projects
            row(s, 2, date, "4818", "ACCOUNT_OPENING", "Hebert", "Mutema", null, "0773103214", null, null, null, null,
                    null, "MDA2606ME", "BOREHOLE", "Borehole drilling", 1500, null, 20, null, "Cash", "Clerk MDA", null);
            row(s, 3, date, "4819", "ACCOUNT_OPENING", null, null, null, null, null, null, null, null,
                    null, "MDA2606ME", null, null, null, null, 30, null, "Cash", "Clerk MDA", null);
            row(s, 4, date, "4820", "ASSET_DEPOSIT", null, null, null, null, null, null, null, null,
                    null, "MDA2606ME", null, null, null, null, 800, null, "EcoCash", "Clerk MDA", null);
            row(s, 5, date, "4821", "ASSET_DEPOSIT", null, null, null, null, null, null, null, null,
                    null, "MDA2606ME", "PIGGERY", "Pig sty", 600, null, 100, null, "Cash", "Clerk MDA", null);
            // subscription by a non-member is refused
            row(s, 6, date, "4822", "SUBSCRIPTION", "Hebert", "Mutema", null, null, null, null, null, null,
                    null, null, null, null, null, null, 1, null, "Cash", "Clerk MDA", null);
            Sheet e = wb.getSheet("Expenditure");
            row(e, 1, date, "V1", "Airtime & travel", "Airtime", "Econet", 1, "Clerk MDA");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            filled = out.toByteArray();
        }

        ExcelImportService.ImportResult first = importer.importReturn(new ByteArrayInputStream(filled), mda.getId());
        assertThat(first.getPosted()).as(first.rows().toString()).isEqualTo(6);
        assertThat(first.getErrors()).as(first.rows().toString()).isEqualTo(1);

        Client vet = clients.findByBranchAndFullName(mda.getId(), "Cyrilo Chizengwe").get(0);
        assertThat(vet.isSaccoMember()).isTrue();
        assertThat(vet.getCategory()).isEqualTo(MemberCategory.WAR_VETERAN);
        assertThat(vet.isJoiningFeePaid()).isTrue();
        assertThat(vet.getDistrict()).isEqualTo("Harare"); // assisted by Marondera, lives outside our branch areas
        assertThat(vet.getBranch().getCode()).isEqualTo("MDA");

        AssetAccount a = accounts.findByAccountNoIgnoreCase("MDA2606ME").orElseThrow();
        assertThat(a.getClient().isSaccoMember()).isFalse();
        assertThat(a.isActive()).isTrue();
        List<Project> ps = projects.findByAccountIdOrderByIdAsc(a.getId());
        assertThat(ps).extracting(Project::getAssetType).containsExactly(AssetType.BOREHOLE, AssetType.PIGGERY);
        assertThat(ps.get(0).getTotalDeposited()).isEqualByComparingTo("800");
        assertThat(ps.get(0).getStatus()).isEqualTo(ProjectStatus.THRESHOLD_MET);
        assertThat(ps.get(1).getTotalDeposited()).isEqualByComparingTo("100");

        ExcelImportService.ImportResult again = importer.importReturn(new ByteArrayInputStream(filled), mda.getId());
        assertThat(again.getPosted()).isZero();
        assertThat(again.getSkipped()).isEqualTo(6);

        assertThat(export.masterRegister(LocalDate.now())).isNotEmpty();

        // the same file uploaded under another branch is refused
        Branch wed = branches.findByCode("WED").orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importer.importReturn(new ByteArrayInputStream(filled), wed.getId()))
                .hasMessageContaining("MDA's file");
    }

    @Test
    void summarySheetAddsUpTheClerksEntries() throws Exception {
        Branch mtk = branches.findByCode("MTK").orElseThrow();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(export.districtReturnTemplate(mtk.getId())))) {
            Sheet s = wb.getSheet("Receipts");
            LocalDate d = LocalDate.of(2026, 10, 5);
            String[] types = {"JOINING_FEE", "ACCOUNT_OPENING", "ASSET_DEPOSIT", "ASSET_DEPOSIT"};
            double[] amounts = {10, 50, 300, 200};
            for (int i = 0; i < types.length; i++) {
                Row r = s.getRow(i + 1);
                r.getCell(0).setCellValue(d);
                r.createCell(2).setCellValue(types[i]);
                r.getCell(17).setCellValue(amounts[i]);
            }
            Row e = wb.getSheet("Expenditure").getRow(1);
            e.getCell(0).setCellValue(d);
            e.getCell(5).setCellValue(5);
            Sheet sum = wb.getSheet("Summary");
            sum.getRow(2).getCell(1).setCellValue(d);
            sum.getRow(3).getCell(1).setCellValue(d);
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            ev.evaluateAll();
            java.util.Map<String, Double> byLabel = new java.util.HashMap<>();
            for (Row r : sum) {
                if (r.getCell(0) == null || r.getCell(2) == null || r.getCell(2).getCellType() != CellType.FORMULA) continue;
                byLabel.put(r.getCell(0).getStringCellValue(), r.getCell(2).getNumericCellValue());
            }
            assertThat(byLabel.get("Asset finance deposit")).isEqualTo(500);
            assertThat(byLabel.get("TOTAL RECEIVED")).isEqualTo(560);
            assertThat(byLabel.get("TOTAL SPENT (Expenditure)")).isEqualTo(5);
            assertThat(byLabel.get("NET CASH (received − spent)")).isEqualTo(555);
        }
    }

    @Test
    void checkColumnExplainsWhatIsWrong() throws Exception {
        Branch gmz = branches.findByCode("GMZ").orElseThrow();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(export.districtReturnTemplate(gmz.getId())))) {
            Sheet s = wb.getSheet("Receipts");
            LocalDate d = LocalDate.of(2026, 10, 5);
            Object[][] rows = {
                    // type, account, name, category, amount → expected check
                    {"ASSET_DEPOSIT", null, "Rudo", null, 100.0, "Account No needed"},
                    {"JOINING_FEE", null, "Tendai", null, 10.0, "Category needed (members only)"},
                    {"JOINING_FEE", null, "Tendai", "WAR_VETERAN", 20.0, "Joining fee is $10"},
                    {"ACCOUNT_OPENING", "GMZ2601ME", "Rudo", null, 60.0, "Opening fee is at most $50"},
                    {"ACCOUNT_OPENING", "GMZ2601ME", "Rudo", null, 50.0, "OK"},
                    {"ASSET_DEPOSIT", "GMZ2601ME", null, null, 300.0, "OK"},
                    {"LOAN_REPAYMENT", "GMZ2699ME", null, null, 40.0, "Account No not on the Accounts list: check it"},
            };
            for (int i = 0; i < rows.length; i++) {
                Row r = s.getRow(i + 1);
                r.getCell(0).setCellValue(d);
                r.getCell(1).setCellValue("R" + i);
                r.getCell(2).setCellValue((String) rows[i][0]);
                if (rows[i][1] != null) r.getCell(12).setCellValue((String) rows[i][1]);
                if (rows[i][2] != null) r.getCell(3).setCellValue((String) rows[i][2]);
                if (rows[i][3] != null) r.getCell(11).setCellValue((String) rows[i][3]);
                r.getCell(17).setCellValue((Double) rows[i][4]);
            }
            Row missingDate = s.getRow(rows.length + 1);
            missingDate.getCell(1).setCellValue("R99");
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            ev.evaluateAll();
            for (int i = 0; i < rows.length; i++) {
                assertThat(s.getRow(i + 1).getCell(22).getStringCellValue()).as("row " + (i + 2)).isEqualTo(rows[i][5]);
            }
            assertThat(s.getRow(rows.length + 1).getCell(22).getStringCellValue()).isEqualTo("Date missing");
            assertThat(s.getRow(rows.length + 2).getCell(22).getStringCellValue()).isEmpty(); // untouched rows stay blank
            assertThat(s.getDataValidations()).hasSizeGreaterThanOrEqualTo(10);
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
