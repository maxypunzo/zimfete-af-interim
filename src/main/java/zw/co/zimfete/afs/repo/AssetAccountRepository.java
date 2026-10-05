package zw.co.zimfete.afs.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import zw.co.zimfete.afs.domain.AssetAccount;

public interface AssetAccountRepository extends JpaRepository<AssetAccount, Long> {
    Optional<AssetAccount> findByAccountNoIgnoreCase(String accountNo);

    boolean existsByAccountNoIgnoreCase(String accountNo);

    List<AssetAccount> findByMemberIdOrderByOpenedDateDesc(Long memberId);

    List<AssetAccount> findAllByOrderByOpenedDateDescIdDesc();

    @Query("select count(a) from AssetAccount a where a.openedDate between :from and :to and (:branchId is null or a.branch.id = :branchId)")
    long countOpened(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("branchId") Long branchId);
}
