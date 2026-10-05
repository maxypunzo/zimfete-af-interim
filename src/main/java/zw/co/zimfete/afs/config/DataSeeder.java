package zw.co.zimfete.afs.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import zw.co.zimfete.afs.domain.Branch;
import zw.co.zimfete.afs.repo.BranchRepository;

/** Creates the seven branches on first start. Clerk names are filled in on the Branches page. */
@Component
public class DataSeeder implements ApplicationRunner {
    private final BranchRepository branches;

    public DataSeeder(BranchRepository branches) {
        this.branches = branches;
    }

    @Override
    public void run(ApplicationArguments args) {
        // codes follow the existing account numbers (MRE2601ME, MDA2601ME, WED2601ME)
        seed("MRE", "Murehwa (Macheke)", "Murehwa", true);
        seed("MDA", "Marondera", "Marondera", false);
        seed("WED", "Wedza", "Hwedza", false);
        seed("MTK", "Mutoko", "Mutoko", false);
        seed("MDZ", "Mudzi", "Mudzi", false);
        seed("GMZ", "Goromonzi", "Goromonzi", false);
        seed("UMP", "UMP", "Uzumba-Maramba-Pfungwe", false);
    }

    private void seed(String code, String name, String district, boolean hq) {
        if (branches.findByCode(code).isEmpty()) branches.save(new Branch(code, name, district, hq));
    }
}
