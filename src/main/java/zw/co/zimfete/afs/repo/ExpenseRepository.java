package zw.co.zimfete.afs.repo;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import zw.co.zimfete.afs.domain.Expense;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {
    @Query("""
            select e from Expense e
            where e.expenseDate between :from and :to
              and (:branchId is null or e.branch.id = :branchId)
            order by e.expenseDate desc, e.id desc
            """)
    List<Expense> find(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("branchId") Long branchId);

    boolean existsByBranchIdAndVoucherNoIgnoreCaseAndExpenseDate(Long branchId, String voucherNo, LocalDate date);
}
