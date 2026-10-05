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
        seed("MRW", "Murehwa (Macheke)", "Murehwa", true);
        seed("MRD", "Marondera", "Marondera", false);
        seed("MTK", "Mutoko", "Mutoko", false);
        seed("MDZ", "Mudzi", "Mudzi", false);
        seed("GMZ", "Goromonzi", "Goromonzi", false);
        seed("UMP", "UMP", "Uzumba-Maramba-Pfungwe", false);
        seed("HWZ", "Hwedza", "Hwedza", false);
    }

    private void seed(String code, String name, String district, boolean hq) {
        if (branches.findByCode(code).isEmpty()) branches.save(new Branch(code, name, district, hq));
    }
}
