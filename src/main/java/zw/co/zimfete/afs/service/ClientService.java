package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.BranchRepository;
import zw.co.zimfete.afs.repo.ClientRepository;

/**
 * One screen, one save: capturing a client can also enrol them as a SACCO member (receipting the joining fee
 * and subscriptions) and open their asset finance account with its first project.
 */
@Service
public class ClientService {
    private final ClientRepository clients;
    private final BranchRepository branches;
    private final NumberService numbers;
    private final ReceiptService receiptService;
    private final AccountService accountService;
    private final StatusService status;

    public ClientService(ClientRepository clients, BranchRepository branches, NumberService numbers,
                         ReceiptService receiptService, AccountService accountService, StatusService status) {
        this.clients = clients;
        this.branches = branches;
        this.numbers = numbers;
        this.receiptService = receiptService;
        this.accountService = accountService;
        this.status = status;
    }

    @Transactional
    public Client register(RegistrationRequest req, String source) {
        if (isBlank(req.getFirstName())) throw new BusinessException("Name is required.");
        String nid = normaliseId(req.getNationalId());
        if (nid != null) {
            clients.findByNationalIdIgnoreCase(nid).ifPresent(existing -> {
                throw new BusinessException("ID " + nid + " is already registered to " + existing.getFullName()
                        + " (" + existing.getClientNo() + ").");
            });
        }
        if (req.getBranchId() == null) throw new BusinessException("Choose a branch.");
        Branch b = branches.findById(req.getBranchId()).orElseThrow(() -> new BusinessException("Choose a branch."));
        LocalDate registered = req.getDateRegistered() != null ? req.getDateRegistered() : LocalDate.now();

        Client c = new Client();
        c.setClientNo(numbers.clientNo(b, clients::existsByClientNo));
        c.setFirstName(req.getFirstName().trim());
        c.setSurname(trim(req.getSurname()));
        c.setNationalId(nid);
        c.setGender(trim(req.getGender()));
        c.setPhone(trim(req.getPhone()));
        c.setVillage(trim(req.getVillage()));
        c.setWard(trim(req.getWard()));
        c.setDistrict(isBlank(req.getDistrict()) ? b.getDistrict() : req.getDistrict().trim());
        c.setNextOfKin(trim(req.getNextOfKin()));
        c.setBranch(b);
        c.setDateRegistered(registered);
        c.setCategory(req.getCategory() != null ? req.getCategory() : MemberCategory.NOT_VETERAN);
        c.setNotes(trim(req.getNotes()));
        clients.saveAndFlush(c);

        if (req.isJoinSacco()) {
            MembershipRequest m = req.getMembership();
            if (m.getCategory() == null) m.setCategory(c.getCategory());
            m.setMemberSince(registered);
            if (isBlank(m.getCapturedBy())) m.setCapturedBy(req.getCapturedBy());
            m.setPaymentMethod(req.getPaymentMethod());
            enrol(c.getId(), m, source);
        }
        if (req.isOpenAccount()) {
            AccountRequest acc = req.getAccount();
            acc.setOpenedDate(registered);
            if (isBlank(acc.getOpenedBy())) acc.setOpenedBy(req.getCapturedBy());
            acc.setPaymentMethod(req.getPaymentMethod());
            ProjectRequest p = req.getProject();
            p.setPaymentMethod(req.getPaymentMethod());
            if (isBlank(p.getCapturedBy())) p.setCapturedBy(acc.getOpenedBy());
            accountService.open(c.getId(), acc, p.isEmpty() ? null : p, source);
        }
        return c;
    }

    /** Makes a veteran-community client a SACCO member and receipts what they paid. */
    @Transactional
    public Client enrol(Long clientId, MembershipRequest req, String source) {
        Client c = get(clientId);
        if (c.isSaccoMember()) throw new BusinessException(c.getFullName() + " is already a SACCO member.");
        MemberCategory cat = req.getCategory() != null ? req.getCategory() : c.getCategory();
        if (cat == null || !cat.isVeteranCommunity()) {
            throw new BusinessException("SACCO membership is for the veteran community: choose the category (war veteran, war collaborator, widow, descendant...).");
        }
        LocalDate since = req.getMemberSince() != null ? req.getMemberSince() : LocalDate.now();
        c.setCategory(cat);
        c.setSaccoMember(true);
        c.setMemberSince(since);
        if (!isBlank(req.getVeteranRef())) c.setVeteranRef(req.getVeteranRef().trim());
        if (!isBlank(req.getRelatedVeteran())) c.setRelatedVeteran(req.getRelatedVeteran().trim());
        clients.saveAndFlush(c);

        if (req.isPayJoiningFee()) {
            receiptService.record(fee(c, since, ReceiptType.JOINING_FEE, ReceiptType.JOINING_FEE.getStandardAmount(), req.getJoiningReceiptNo(), req, source));
        }
        if (req.getSubsMonths() != null && req.getSubsMonths() > 0) {
            ReceiptRequest subs = fee(c, since, ReceiptType.SUBSCRIPTION,
                    ReceiptType.SUBSCRIPTION.getStandardAmount().multiply(BigDecimal.valueOf(req.getSubsMonths())), req.getSubsReceiptNo(), req, source);
            subs.setMonths(req.getSubsMonths());
            receiptService.record(subs);
        }
        status.recalcClient(c);
        return c;
    }

    @Transactional
    public Client update(Long id, Client form) {
        Client c = get(id);
        if (isBlank(form.getFirstName())) throw new BusinessException("Name is required.");
        String nid = normaliseId(form.getNationalId());
        if (nid != null) {
            clients.findByNationalIdIgnoreCase(nid).filter(o -> !o.getId().equals(id)).ifPresent(o -> {
                throw new BusinessException("ID " + nid + " belongs to " + o.getFullName() + ".");
            });
        }
        MemberCategory cat = form.getCategory() != null ? form.getCategory() : MemberCategory.NOT_VETERAN;
        if (c.isSaccoMember() && !cat.isVeteranCommunity()) {
            throw new BusinessException(c.getFullName() + " is a SACCO member, so the category must be a veteran-community one.");
        }
        c.setFirstName(form.getFirstName().trim());
        c.setSurname(trim(form.getSurname()));
        c.setNationalId(nid);
        c.setGender(trim(form.getGender()));
        c.setPhone(trim(form.getPhone()));
        c.setVillage(trim(form.getVillage()));
        c.setWard(trim(form.getWard()));
        c.setDistrict(trim(form.getDistrict()));
        c.setNextOfKin(trim(form.getNextOfKin()));
        c.setCategory(cat);
        c.setVeteranRef(trim(form.getVeteranRef()));
        c.setRelatedVeteran(trim(form.getRelatedVeteran()));
        c.setNotes(trim(form.getNotes()));
        return clients.save(c);
    }

    public Client get(Long id) {
        return clients.findById(id).orElseThrow(() -> new BusinessException("Client not found."));
    }

    /** Zimbabwe IDs are written many ways (63-123456 A 75, 63123456A75); store them without spaces/dashes. */
    public static String normaliseId(String id) {
        if (id == null || id.isBlank()) return null;
        return id.replaceAll("[\\s-]", "").toUpperCase();
    }

    private static ReceiptRequest fee(Client c, LocalDate date, ReceiptType type, BigDecimal amount, String receiptNo,
                                      MembershipRequest req, String source) {
        ReceiptRequest r = new ReceiptRequest();
        r.setBranchId(c.getBranch().getId());
        r.setClientId(c.getId());
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
