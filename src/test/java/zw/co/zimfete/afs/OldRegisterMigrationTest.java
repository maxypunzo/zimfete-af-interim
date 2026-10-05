package zw.co.zimfete.afs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.*;
import zw.co.zimfete.afs.service.OldRegisterMigrationService;
import zw.co.zimfete.afs.service.ReportService;

/** The one-off import of the previous AFM's workbook, on a small workbook laid out like hers. */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OldRegisterMigrationTest {
    @Autowired OldRegisterMigrationService migration;
    @Autowired AssetAccountRepository accounts;
    @Autowired ProjectRepository projects;
    @Autowired ClientRepository clients;
    @Autowired BranchRepository branches;
    @Autowired ReportService reports;

    private static final LocalDate AS_AT = LocalDate.of(2026, 9, 30);

    @Test
    void importsAsRecordedAndListsWhatToConfirm() throws Exception {
        OldRegisterMigrationService.Result r = migration.migrate(new ByteArrayInputStream(oldWorkbook()), AS_AT);

        // two rows on one account (same phone) → one client, one account, two projects; fee recorded twice is flagged
        AssetAccount viola = accounts.findByAccountNoIgnoreCase("MRE2601ME").orElseThrow();
        assertThat(viola.getClient().getFullName()).isEqualTo("VIOLA MUSHAWEVATO");
        assertThat(viola.getClient().getCategory()).isEqualTo(MemberCategory.UNKNOWN);
        assertThat(viola.getClient().getPhone()).isEqualTo("773542968"); // as recorded, no 0 added
        assertThat(projects.findByAccountIdOrderByIdAsc(viola.getId())).extracting(Project::getAssetDescription)
                .containsExactly("BOREHOLE & INSTALLATION", "FENCING PROJECT");
        assertThat(viola.getOpeningFeePaid()).isEqualByComparingTo("100");

        // loan exactly as in the Repayments sheet (20%, not recalculated at 30%)
        Project loan = projects.findByAccountIdOrderByIdAsc(viola.getId()).get(0);
        assertThat(loan.getStatus()).isEqualTo(ProjectStatus.IN_PROGRESS);
        assertThat(loan.getLoanTerms().totalRepayable()).isEqualByComparingTo("1285");
        assertThat(loan.getLoanTerms().monthlyInstalment()).isEqualByComparingTo("150");
        assertThat(loan.getTotalRepaid()).isEqualByComparingTo("450");
        assertThat(loan.getLoanBalance()).isEqualByComparingTo("835");
        assertThat(loan.getTotalDeposited()).isEqualByComparingTo("1070");
        assertThat(loan.getQuotationCost()).isEqualByComparingTo("2141");
        assertThat(loan.getProjectStartDate()).isNull(); // not recorded, left blank

        // duplicate account number for the same client: kept the first as decided
        assertThat(accounts.findByAccountNoIgnoreCase("MRE2622ME")).isEmpty();
        AssetAccount kahuni = accounts.findByAccountNoIgnoreCase("MRE2614ME").orElseThrow();
        assertThat(projects.findByAccountIdOrderByIdAsc(kahuni.getId()).get(0).getTotalDeposited()).isEqualByComparingTo("1030");

        // incomplete record using a number already in the client database gets a new one
        AssetAccount mukuzo = accounts.findByAccountNoIgnoreCase("MRE2627ME").orElseThrow();
        assertThat(mukuzo.getClient().getFullName()).isEqualTo("MERCY MUKUZO");
        AssetAccount chaitezvi = accounts.findByClientIdOrderByOpenedDateDesc(
                clients.search(null, "CHAITEZVI", null, null).get(0).getId()).get(0);
        assertThat(chaitezvi.getAccountNo()).isNotEqualTo("MRE2627ME").startsWith("MRE");
        assertThat(chaitezvi.getOpenedDate()).isNull(); // blank in the old sheet
        assertThat(chaitezvi.isActive()).isFalse();

        // sheets disagree on the cost: left blank and listed
        Project solar = projects.findByAccountIdOrderByIdAsc(mukuzo.getId()).get(0);
        assertThat(solar.getQuotationCost()).isNull();
        assertThat(r.toConfirm()).anyMatch(i -> i.message().contains("costs differ"));
        // different deposit on another sheet: client database kept, listed
        assertThat(r.toConfirm()).anyMatch(i -> i.message().contains("Amount deposited $1200 here vs $900"));

        // Harare: kept as a historical location, not offered as a branch
        Branch harare = branches.findByCode("HRE").orElseThrow();
        assertThat(harare.isOperating()).isFalse();
        assertThat(accounts.findByAccountNoIgnoreCase("HRE2601HRE").orElseThrow().getBranch().getCode()).isEqualTo("HRE");

        // approval sheet "done" → approved; inexact names are not guessed
        AssetAccount kuodza = accounts.findByAccountNoIgnoreCase("MRE2626ME").orElseThrow();
        assertThat(projects.findByAccountIdOrderByIdAsc(kuodza.getId()).get(0).getStatus()).isEqualTo(ProjectStatus.APPROVED);
        assertThat(r.toConfirm()).anyMatch(i -> i.who().equals("Marry Mamhunze") && i.message().contains("Possible match: MARY MAMHUNZE"));

        // someone with a loan but missing from the client database
        assertThat(r.toConfirm()).anyMatch(i -> i.who().equals("ASHUATE ZADZI") && i.message().contains("not in the client database"));

        // balances b/f are not cash; the old cash book is, and matches her August figures
        ReportService.CashReport aug = reports.cashReport(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null);
        assertThat(aug.receiptsTotal()).isEqualByComparingTo("285");
        assertThat(aug.expensesTotal()).isEqualByComparingTo("2");
        assertThat(aug.disbursementsTotal()).isEqualByComparingTo("1800");
        assertThat(reports.cashReport(AS_AT, AS_AT, null).receiptsTotal()).isEqualByComparingTo("0");
        assertThat(reports.monthly(YearMonth.of(2026, 8), null).getSurplus()).isEqualByComparingTo("48"); // fees 50 − airtime 2

        assertThat(r.clients()).isEqualTo(8);
        assertThat(migration.reportWorkbook(r)).isNotEmpty();
        assertThatThrownBy(() -> migration.migrate(new ByteArrayInputStream(oldWorkbook()), AS_AT)).hasMessageContaining("already been imported");
    }

    // ---------------------------------------------------------------- a small workbook in the old layout

    private static byte[] oldWorkbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            Sheet db = wb.createSheet("client database");
            put(db, date, 0, "DATE", "NAME ", "CONTACT", "ACCOUNT NUMBER", "PURPOSE", "WARD OR AREA", "ACTIVE ", "ACC OPENING FEES", "AMOUNT DEPOSITED");
            put(db, date, 1, null, "MUREHWA DISTRICT");
            put(db, date, 3, d(2026, 2, 4), "VIOLA MUSHAWEVATO", 773542968, "MRE2601ME", "BOREHOLE & INSTALLATION", "WARD 19", "YES", 50, 1070);
            put(db, date, 4, d(2026, 2, 9), "MARY MAMHUNZE ", 717430504, "MRE2304ME", "BOREHOLE & INSTALLATION", "MUG FARM", "YES", 50, 900);
            put(db, date, 5, d(2026, 4, 2), "VIOLA MUSHAWEVATO 2", 773542968, "MRE2601ME", "FENCING PROJECT", "WARD 19", "YES", 50, 500);
            put(db, date, 6, d(2025, 5, 15), "PEFENIA KAHUNI", 772832799, "MRE2614ME", "BOREHOLE", "WARD 9", "YES ", 50, 1030);
            put(db, date, 7, d(2025, 6, 12), "PEFENIA KAHUNI", 772832799, "MRE2622ME", "Borehole", "WARD 9", "YES ", 50, 1030);
            put(db, date, 8, d(2025, 7, 30), "NICHOLAS KUODZA", 774463410, "MRE2626ME", "DRIP SYSTEM", "ST COLUMBUS", "YES", 50, 100);
            put(db, date, 9, d(2026, 8, 1), " MERCY MUKUZO", 775887607, "MRE2627ME", " SOLAR INSTALLATION", "NHOWE", "YES", 50, 1000);
            put(db, date, 11, null, "HARARE DISTRICT");
            put(db, date, 12, d(2026, 7, 14), "TAZVIWINGA FOROMA", 772591809, "HRE2601HRE", "BOREHOLE", "BANKET", "YES", 50, 3000);
            put(db, date, 14, "TOTALS", null, null, null, null, null, null, 300, 7630);

            Sheet inc = wb.createSheet("Incomplete records");
            put(inc, date, 0, "DATE", "NAME ", "CONTACT", "ACCOUNT NUMBER", "PURPOSE", "WARD OR AREA", "ACTIVE ", "ACC OPENING FEES", "AMOUNT DEPOSITED");
            put(inc, date, 1, null, "LANCELOT CHAITEZVI", 719733327, "MRE2627ME", "BOREHOLE & INSTALLATION", "WARD 19", "NO");

            Sheet dep = wb.createSheet("Deposits");
            put(dep, date, 0, "DATE", "NAME ", "CONTACT", "ACCOUNT NUMBER", "PURPOSE", "WARD OR AREA", "ACTIVE ", "ACC OPENING FEES", "TOTAL COST", "AMT REQUIRED", "AMOUNT DEPOSITED");
            put(dep, date, 1, d(2026, 2, 9), "MARY MAMHUNZE ", 717430504, "MRE2304ME", "BOREHOLE & INSTALLATION", "MUG FARM", "YES", 50, 3100, 1550, 1200);
            put(dep, date, 2, d(2026, 8, 1), " MERCY MUKUZO", 775887607, "MRE2627ME", " SOLAR INSTALLATION", "NHOWE", "YES", 50, 1700, 700, 1000);

            Sheet rep = wb.createSheet("Repayments");
            put(rep, date, 0, "NAME ", "CONTACT", "ACCOUNT NUMBER", "PURPOSE", "WARD OR AREA", "TOTAL PROJECT COST", "AMT DEPOSITED", "TOTAL LOAN", "GRAND TOTAL", "INSTALLMENTS", "AMT PAID", "BALANCE");
            put(rep, date, 1, "VIOLA MUSHAWEVATO", 773542968, "MRE2601ME", "BOREHOLE & INSTALLATION", "WARD 19", 2141, 1070, 1071, 1285, 150, 450, 835);
            put(rep, date, 2, "PEFENIA KAHUNI", 772832799, "MRE2622ME", "Borehole", "WARD 9", 1500, 1030, 470, 551, 92, 0, 551);
            put(rep, date, 3, "ASHUATE ZADZI", 772272627, null, "BOREHOLE DRILLING", "CHIRINDA FARM", 1413, 1200, 213, 264, 44, 0, 264);
            put(rep, date, 4, " MERCY MUKUZO", 775887607, "MRE2631ME", " SOLAR INSTALLATION", "NHOWE", 1400, 1000);

            Sheet ap = wb.createSheet("Project approval");
            put(ap, date, 0, null, "CLIENTS WHO NEED APPROVAL FROM ZIMFETE COMMITTEE");
            put(ap, date, 2, null, "NAME", "AREA", "PROJECT", "AMOUNT DEPOSITED ", "LOAN REQUIREMENT", "PROJECT COST", "PROPOSED DATE");
            put(ap, date, 3, null, "Nicholas Kuodza", "St Columbus", "Drip system", 100, 480, 580, "done");
            put(ap, date, 4, null, "Marry Mamhunze", "ward 9", "Borehole", 1200, 1900, 3100, "September");

            Sheet cash = wb.createSheet("daily inflow Aug");
            put(cash, date, 0, "DATE", "ACTIVITY", "AMOUNT $", "RECEIPT NUMBER", "CLIENT");
            put(cash, date, 1, d(2026, 8, 1), "Acc deposit", 35, 4803, "Mr Kuodza");
            put(cash, date, 2, null, "Acc opening", 50, 4804, "Mrs Mukuzo");
            put(cash, date, 3, null, "Acc deposit", 200, 4805, "Mrs Mukuzo");

            Sheet cf = wb.createSheet("August cashflow");
            put(cf, date, 0, null, "AUGUST CASHFLOW");
            put(cf, date, 1, null, d(2026, 8, 1), d(2026, 8, 2), d(2026, 8, 3), d(2026, 8, 4), d(2026, 8, 5), d(2026, 8, 6), d(2026, 8, 7));
            put(cf, date, 8, "Total inflow", 285, 0, 0, 0, 0, 0, 0);
            put(cf, date, 12, "Airtime & travel", 1, 1);
            put(cf, date, 13, "Loan (projects)", null, null, null, null, null, null, 1800);

            Sheet out = wb.createSheet("Aug outflow");
            put(out, date, 0, "NAME", "PROJECT", "TOTAL AMOUNT", "DATE");
            put(out, date, 2, "MR FOROMA", "BOREHOLE DRILLING", 1800, d(2026, 8, 7));
            put(out, date, 3, "TOTAL", null, 1800);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            wb.write(bytes);
            return bytes.toByteArray();
        }
    }

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    private static void put(Sheet s, CellStyle date, int row, Object... values) {
        Row r = s.createRow(row);
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            if (v == null) continue;
            Cell c = r.createCell(i);
            if (v instanceof Number n) c.setCellValue(n.doubleValue());
            else if (v instanceof LocalDate ld) {
                c.setCellValue(ld);
                c.setCellStyle(date);
            } else c.setCellValue(v.toString());
        }
    }

}
