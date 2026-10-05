package zw.co.zimfete.afs.repo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import zw.co.zimfete.afs.domain.Branch;

public interface BranchRepository extends JpaRepository<Branch, Long> {
    Optional<Branch> findByCode(String code);

    Optional<Branch> findFirstByHeadOfficeTrue();

    List<Branch> findAllByOrderByHeadOfficeDescNameAsc();
}
