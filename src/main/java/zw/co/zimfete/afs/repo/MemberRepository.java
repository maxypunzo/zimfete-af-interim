package zw.co.zimfete.afs.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import zw.co.zimfete.afs.domain.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByNationalIdIgnoreCase(String nationalId);

    boolean existsByMemberNo(String memberNo);

    @Query("""
            select m from Member m
            where (:branchId is null or m.branch.id = :branchId)
              and (:q is null or lower(m.firstName) like lower(concat('%', :q, '%'))
                   or lower(m.surname) like lower(concat('%', :q, '%'))
                   or lower(m.nationalId) like lower(concat('%', :q, '%'))
                   or lower(m.memberNo) like lower(concat('%', :q, '%'))
                   or m.phone like concat('%', :q, '%'))
            order by m.dateJoined desc, m.id desc
            """)
    List<Member> search(@Param("branchId") Long branchId, @Param("q") String q);

    long countByBranchId(Long branchId);

    @Query("select count(m) from Member m where m.dateJoined between :from and :to and (:branchId is null or m.branch.id = :branchId)")
    long countJoined(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("branchId") Long branchId);

    List<Member> findByBranchIdOrderBySurnameAsc(Long branchId);
}
