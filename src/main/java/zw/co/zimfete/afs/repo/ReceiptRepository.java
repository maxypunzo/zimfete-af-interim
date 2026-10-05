package zw.co.zimfete.afs.repo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import zw.co.zimfete.afs.domain.Receipt;
import zw.co.zimfete.afs.domain.ReceiptType;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {
    boolean existsByBranchIdAndReceiptNoIgnoreCase(Long branchId, String receiptNo);

    @Query("""
            select r from Receipt r
            where r.receiptDate between :from and :to
              and (:branchId is null or r.branch.id = :branchId)
              and (:type is null or r.type = :type)
            order by r.receiptDate desc, r.id desc
            """)
    List<Receipt> find(@Param("from") LocalDate from, @Param("to") LocalDate to,
                       @Param("branchId") Long branchId, @Param("type") ReceiptType type);

    List<Receipt> findByClientIdOrderByReceiptDateDescIdDesc(Long clientId);

    List<Receipt> findByAccountIdOrderByReceiptDateAscIdAsc(Long accountId);

    List<Receipt> findByProjectIdOrderByReceiptDateAscIdAsc(Long projectId);

    @Query("select coalesce(sum(r.amount), 0) from Receipt r where r.project.id = :projectId and r.type = :type and r.reversed = false")
    BigDecimal sumForProject(@Param("projectId") Long projectId, @Param("type") ReceiptType type);

    @Query("select coalesce(sum(r.amount), 0) from Receipt r where r.account.id = :accountId and r.type = zw.co.zimfete.afs.domain.ReceiptType.ACCOUNT_OPENING and r.reversed = false")
    BigDecimal sumOpeningFees(@Param("accountId") Long accountId);

    @Query("select coalesce(sum(r.months), 0) from Receipt r where r.client.id = :clientId and r.type = zw.co.zimfete.afs.domain.ReceiptType.SUBSCRIPTION and r.reversed = false")
    long sumSubscriptionMonths(@Param("clientId") Long clientId);

    @Query("select count(r) from Receipt r where r.client.id = :clientId and r.type = zw.co.zimfete.afs.domain.ReceiptType.JOINING_FEE and r.reversed = false")
    long countJoiningFees(@Param("clientId") Long clientId);
}
