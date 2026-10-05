package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.BranchRepository;
import zw.co.zimfete.afs.repo.MemberRepository;

/**
 * One screen, one save: capturing a new member also receipts the joining fee and subscription and,
 * optionally, opens their asset finance account. That replaces the old client sheet + deposit sheet +
 * income sheet triple entry.
 */
@Service
public class MemberService {
    private final MemberRepository members;
    private final BranchRepository branches;
    private final NumberService numbers;
    private final ReceiptService receiptService;
    private final AccountService accountService;

    public MemberService(MemberRepository members, BranchRepository branches, NumberService numbers,
                         ReceiptService receiptService, AccountService accountService) {
        this.members = members;
        this.branches = branches;
        this.numbers = numbers;
        this.receiptService = receiptService;
        this.accountService = accountService;
    }

    @Transactional
    public Member register(RegistrationRequest req, String source) {
        if (isBlank(req.getFirstName()) || isBlank(req.getSurname())) throw new BusinessException("First name and surname are required.");
        if (isBlank(req.getNationalId())) throw new BusinessException("National ID is required.");
        String nid = normaliseId(req.getNationalId());
        members.findByNationalIdIgnoreCase(nid).ifPresent(existing -> {
            throw new BusinessException("ID " + nid + " is already registered to " + existing.getFullName()
                    + " (" + existing.getMemberNo() + ").");
        });
        Branch b = branches.findById(req.getBranchId()).orElseThrow(() -> new BusinessException("Choose a branch."));
        LocalDate joined = req.getDateJoined() != null ? req.getDateJoined() : LocalDate.now();

        Member m = new Member();
        m.setMemberNo(numbers.memberNo(b, members::existsByMemberNo));
        m.setFirstName(req.getFirstName().trim());
        m.setSurname(req.getSurname().trim());
        m.setNationalId(nid);
        m.setGender(req.getGender());
        m.setPhone(trim(req.getPhone()));
        m.setVillage(trim(req.getVillage()));
        m.setWard(trim(req.getWard()));
        m.setDistrict(isBlank(req.getDistrict()) ? b.getDistrict() : req.getDistrict().trim());
        m.setNextOfKin(trim(req.getNextOfKin()));
        m.setBranch(b);
        m.setDateJoined(joined);
        m.setNotes(req.getNotes());
        members.saveAndFlush(m);

        if (req.isPayJoiningFee()) {
            receiptService.record(simple(b, m, joined, ReceiptType.JOINING_FEE, ReceiptType.JOINING_FEE.getStandardAmount(),
                    req.getJoiningReceiptNo(), req, source));
        }
        if (req.getSubsMonths() != null && req.getSubsMonths() > 0) {
            ReceiptRequest subs = simple(b, m, joined, ReceiptType.SUBSCRIPTION,
                    ReceiptType.SUBSCRIPTION.getStandardAmount().multiply(BigDecimal.valueOf(req.getSubsMonths())),
                    req.getSubsReceiptNo(), req, source);
            subs.setMonths(req.getSubsMonths());
            receiptService.record(subs);
        }
        if (req.isOpenAccount()) {
            AccountRequest acc = req.getAccount();
            acc.setOpenedDate(joined);
            if (isBlank(acc.getOpenedBy())) acc.setOpenedBy(req.getCapturedBy());
            acc.setPaymentMethod(req.getPaymentMethod());
            accountService.open(m.getId(), acc, source);
        }
        return m;
    }

    @Transactional
    public Member update(Long id, Member form) {
        Member m = members.findById(id).orElseThrow(() -> new BusinessException("Member not found."));
        if (isBlank(form.getFirstName()) || isBlank(form.getSurname()) || isBlank(form.getNationalId())) {
            throw new BusinessException("First name, surname and national ID are required.");
        }
        String nid = normaliseId(form.getNationalId());
        members.findByNationalIdIgnoreCase(nid).filter(o -> !o.getId().equals(id)).ifPresent(o -> {
            throw new BusinessException("ID " + nid + " belongs to " + o.getFullName() + ".");
        });
        m.setFirstName(form.getFirstName().trim());
        m.setSurname(form.getSurname().trim());
        m.setNationalId(nid);
        m.setGender(form.getGender());
        m.setPhone(trim(form.getPhone()));
        m.setVillage(trim(form.getVillage()));
        m.setWard(trim(form.getWard()));
        m.setDistrict(trim(form.getDistrict()));
        m.setNextOfKin(trim(form.getNextOfKin()));
        m.setNotes(form.getNotes());
        return members.save(m);
    }

    /** Zimbabwe IDs are written many ways (63-123456 A 75, 63123456A75); store them without spaces/dashes. */
    public static String normaliseId(String id) {
        return id == null ? null : id.replaceAll("[\\s-]", "").toUpperCase();
    }

    private static ReceiptRequest simple(Branch b, Member m, LocalDate date, ReceiptType type, BigDecimal amount,
                                         String receiptNo, RegistrationRequest req, String source) {
        ReceiptRequest r = new ReceiptRequest();
        r.setBranchId(b.getId());
        r.setMemberId(m.getId());
        r.setReceiptDate(date);
        r.setType(type);
        r.setAmount(amount);
        r.setReceiptNo(receiptNo);
        r.setPaymentMethod(req.getPaymentMethod());
        r.setCapturedBy(req.getCapturedBy());
        r.setSource(source);
        return r;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
