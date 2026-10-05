package zw.co.zimfete.afs.web;

import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import zw.co.zimfete.afs.config.AfsProperties;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.BranchRepository;

/** Lists every page needs (branches for dropdowns, enum values, today). */
@ControllerAdvice
public class GlobalModel {
    private final BranchRepository branches;
    private final AfsProperties props;

    public GlobalModel(BranchRepository branches, AfsProperties props) {
        this.branches = branches;
        this.props = props;
    }

    /** Blank form fields are stored as null, not "". */
    @InitBinder
    public void trimStrings(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
    }

    /** Branches offered when capturing. */
    @ModelAttribute("branches")
    public List<Branch> branches() {
        return branches.findAllByOrderByHeadOfficeDescNameAsc().stream().filter(Branch::isOperating).toList();
    }

    /** Branches offered in report filters: also historical locations such as Harare. */
    @ModelAttribute("allBranches")
    public List<Branch> allBranches() {
        return branches.findAllByOrderByHeadOfficeDescNameAsc();
    }

    @ModelAttribute("hqBranchId")
    public Long hqBranchId() {
        return branches.findFirstByHeadOfficeTrue().map(Branch::getId).orElse(null);
    }

    @ModelAttribute("officerName")
    public String officerName() {
        return props.officerName();
    }

    @ModelAttribute("today")
    public LocalDate today() {
        return LocalDate.now();
    }

    @ModelAttribute("assetTypes")
    public AssetType[] assetTypes() {
        return AssetType.values();
    }

    @ModelAttribute("categories")
    public MemberCategory[] categories() {
        return MemberCategory.values();
    }

    @ModelAttribute("veteranCategories")
    public List<MemberCategory> veteranCategories() {
        return java.util.Arrays.stream(MemberCategory.values()).filter(MemberCategory::isVeteranCommunity).toList();
    }

    @ModelAttribute("defaultInterest")
    public java.math.BigDecimal defaultInterest() {
        return props.interestPercent();
    }

    @ModelAttribute("statuses")
    public ProjectStatus[] statuses() {
        return ProjectStatus.values();
    }

    @ModelAttribute("paymentMethods")
    public String[] paymentMethods() {
        return new String[] {"Cash", "EcoCash", "Bank transfer", "Swipe", "InnBucks", "OneMoney"};
    }

    @ModelAttribute("expenseCategories")
    public String[] expenseCategories() {
        return Expense.CATEGORIES;
    }
}
