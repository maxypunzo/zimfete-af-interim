package zw.co.zimfete.afs.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import zw.co.zimfete.afs.domain.Branch;
import zw.co.zimfete.afs.repo.BranchRepository;

@Controller
@RequestMapping("/branches")
public class BranchController {
    private final BranchRepository branches;

    public BranchController(BranchRepository branches) {
        this.branches = branches;
    }

    @GetMapping
    public String list() {
        return "branches";
    }

    @PostMapping("/{id}")
    public String save(@PathVariable Long id, @RequestParam(required = false) String clerkName, RedirectAttributes ra) {
        Branch b = branches.findById(id).orElseThrow();
        b.setClerkName(clerkName == null || clerkName.isBlank() ? null : clerkName.trim());
        branches.save(b);
        ra.addFlashAttribute("message", "Saved clerk for " + b.getName() + ".");
        return "redirect:/branches";
    }
}
