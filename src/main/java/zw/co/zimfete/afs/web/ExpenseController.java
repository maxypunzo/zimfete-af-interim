package zw.co.zimfete.afs.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import zw.co.zimfete.afs.config.AfsProperties;
import zw.co.zimfete.afs.domain.Expense;
import zw.co.zimfete.afs.repo.BranchRepository;
import zw.co.zimfete.afs.repo.ExpenseRepository;
import zw.co.zimfete.afs.service.ReportService;

@Controller
@RequestMapping("/expenses")
public class ExpenseController {
    private final ExpenseRepository expenses;
    private final BranchRepository branches;
    private final AfsProperties props;

    public ExpenseController(ExpenseRepository expenses, BranchRepository branches, AfsProperties props) {
        this.expenses = expenses;
        this.branches = branches;
        this.props = props;
    }

    @GetMapping
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) Long branchId, Model model) {
        LocalDate t = to != null ? to : LocalDate.now();
        LocalDate f = from != null ? from : t.withDayOfMonth(1);
        List<Expense> list = expenses.find(f, t, branchId);
        model.addAttribute("expenses", list);
        model.addAttribute("total", ReportService.sum(list.stream().map(Expense::getAmount)));
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("branchId", branchId);
        return "expenses/list";
    }

    @PostMapping
    public String add(@RequestParam Long branchId, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expenseDate,
                      @RequestParam String category, @RequestParam(required = false) String description,
                      @RequestParam(required = false) String payee, @RequestParam(required = false) String voucherNo,
                      @RequestParam BigDecimal amount, RedirectAttributes ra) {
        if (amount.signum() <= 0) {
            ra.addFlashAttribute("error", "Amount must be greater than zero.");
            return "redirect:/expenses";
        }
        Expense e = new Expense();
        e.setBranch(branches.findById(branchId).orElseThrow());
        e.setExpenseDate(expenseDate);
        e.setCategory(category);
        e.setDescription(description);
        e.setPayee(payee);
        e.setVoucherNo(voucherNo);
        e.setAmount(amount);
        e.setCapturedBy(props.officerName());
        expenses.save(e);
        ra.addFlashAttribute("message", "Expenditure of $" + amount + " recorded.");
        return "redirect:/expenses";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        expenses.deleteById(id);
        ra.addFlashAttribute("message", "Expenditure line deleted.");
        return "redirect:/expenses";
    }
}
