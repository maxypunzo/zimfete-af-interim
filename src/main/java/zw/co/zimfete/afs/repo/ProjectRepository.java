package zw.co.zimfete.afs.repo;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import zw.co.zimfete.afs.domain.Project;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByAccountIdOrderByIdAsc(Long accountId);

    @Query("select p from Project p where p.account.client.id = :clientId order by p.id")
    List<Project> findByClient(@Param("clientId") Long clientId);

    @Query("select p from Project p order by p.account.openedDate desc, p.id desc")
    List<Project> findAllOrdered();

    /** Projects whose funds were disbursed in the period: the "loan (projects)" outflow. */
    @Query("""
            select p from Project p where p.projectStartDate between :from and :to
              and (:branchId is null or p.account.branch.id = :branchId)
            order by p.projectStartDate, p.id
            """)
    List<Project> disbursedBetween(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("branchId") Long branchId);
}
