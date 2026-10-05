package zw.co.zimfete.afs.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import zw.co.zimfete.afs.domain.Client;
import zw.co.zimfete.afs.domain.MemberCategory;

public interface ClientRepository extends JpaRepository<Client, Long> {
    Optional<Client> findByNationalIdIgnoreCase(String nationalId);

    Optional<Client> findByClientNoIgnoreCase(String clientNo);

    boolean existsByClientNo(String clientNo);

    @Query("""
            select c from Client c
            where (:branchId is null or c.branch.id = :branchId)
              and (:members is null or c.saccoMember = :members)
              and (:category is null or c.category = :category)
              and (:q is null or lower(c.firstName) like lower(concat('%', :q, '%'))
                   or lower(c.surname) like lower(concat('%', :q, '%'))
                   or lower(c.nationalId) like lower(concat('%', :q, '%'))
                   or lower(c.clientNo) like lower(concat('%', :q, '%'))
                   or c.phone like concat('%', :q, '%'))
            order by c.dateRegistered desc, c.id desc
            """)
    List<Client> search(@Param("branchId") Long branchId, @Param("q") String q,
                        @Param("members") Boolean members, @Param("category") MemberCategory category);

    /** Used by the import to find a client by name when the return has no national ID. */
    @Query("""
            select c from Client c where c.branch.id = :branchId
              and lower(trim(concat(c.firstName, ' ', coalesce(c.surname, '')))) = lower(:fullName)
            """)
    List<Client> findByBranchAndFullName(@Param("branchId") Long branchId, @Param("fullName") String fullName);

    long countByBranchId(Long branchId);

    long countByBranchIdAndSaccoMemberTrue(Long branchId);

    @Query("select count(c) from Client c where c.dateRegistered between :from and :to and (:branchId is null or c.branch.id = :branchId)")
    long countRegistered(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("branchId") Long branchId);

    @Query("select count(c) from Client c where c.saccoMember = true and c.memberSince between :from and :to and (:branchId is null or c.branch.id = :branchId)")
    long countJoined(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("branchId") Long branchId);
}
