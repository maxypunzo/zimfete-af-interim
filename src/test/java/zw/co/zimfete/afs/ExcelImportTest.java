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
            // Date, Receipt No, Type, First, Surname, ID, Phone, Gender, Village, Ward, Category, Account No, Asset Type,
            // Asset Desc, Quotation, Target Date, Amount, Months, Method, Clerk, Notes
            // a war veteran joins (no national ID in the old registers)
            row(s, 1, date, "4817", "JOINING_FEE", "Cyrilo", "Chizengwe", null, "0772903006", "Male", "Loquate", "8", "WV",
                    null, null, null, null, null, 10, null, "Cash", "Clerk MDA", null);
            // a non-member opens an account, paying the fee in two parts, then deposits for two projects
            row(s, 2, date, "4818", "ACCOUNT_OPENING", "Hebert", "Mutema", null, "0773103214", null, null, null, null,
                    "MDA2606ME", "BOREHOLE", "Borehole drilling", 1500, null, 20, null, "Cash", "Clerk MDA", null);
            row(s, 3, date, "4819", "ACCOUNT_OPENING", null, null, null, null, null, null, null, null,
                    "MDA2606ME", null, null, null, null, 30, null, "Cash", "Clerk MDA", null);
            row(s, 4, date, "4820", "ASSET_DEPOSIT", null, null, null, null, null, null, null, null,
                    "MDA2606ME", null, null, null, null, 800, null, "EcoCash", "Clerk MDA", null);
            row(s, 5, date, "4821", "ASSET_DEPOSIT", null, null, null, null, null, null, null, null,
                    "MDA2606ME", "PIGGERY", "Pig sty", 600, null, 100, null, "Cash", "Clerk MDA", null);
            // subscription by a non-member is refused
            row(s, 6, date, "4822", "SUBSCRIPTION", "Hebert", "Mutema", null, null, null, null, null, null,
                    null, null, null, null, null, 1, null, "Cash", "Clerk MDA", null);
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
