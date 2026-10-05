package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.AssetAccountRepository;
import zw.co.zimfete.afs.repo.ClientRepository;

/** Opening asset finance accounts. Open to any client, member or not. */
@Service
public class AccountService {
    private final AssetAccountRepository accounts;
    private final ClientRepository clients;
    private final NumberService numbers;
    private final ReceiptService receiptService;
    private final ProjectService projectService;

    public AccountService(AssetAccountRepository accounts, ClientRepository clients, NumberService numbers,
                          ReceiptService receiptService, ProjectService projectService) {
        this.accounts = accounts;
        this.clients = clients;
        this.numbers = numbers;
        this.receiptService = receiptService;
        this.projectService = projectService;
    }

    /**
     * Opens an account: generates (or accepts) the account number, receipts what was paid of the $50 opening
     * fee (it may be paid in parts) and, if given, adds the first project.
     */
    @Transactional
    public AssetAccount open(Long clientId, AccountRequest req, ProjectRequest firstProject, String source) {
        Client c = clients.findById(clientId).orElseThrow(() -> new BusinessException("Client not found."));
        Branch b = c.getBranch();
        LocalDate opened = req.getOpenedDate() != null ? req.getOpenedDate() : LocalDate.now();
        BigDecimal fee = req.getOpeningFeeAmount() == null ? BigDecimal.ZERO : req.getOpeningFeeAmount();
        if (fee.signum() < 0 || fee.compareTo(AssetAccount.OPENING_FEE) > 0) {
            throw new BusinessException("Opening fee paid must be between $0 and $" + AssetAccount.OPENING_FEE + ".");
        }

        String no = req.getAccountNo() == null ? "" : req.getAccountNo().trim().toUpperCase();
        if (no.isEmpty()) {
            no = numbers.accountNo(b, opened, accounts::existsByAccountNoIgnoreCase);
        } else if (accounts.existsByAccountNoIgnoreCase(no)) {
            throw new BusinessException("Account number " + no + " is already in use.");
        }

        AssetAccount a = new AssetAccount();
        a.setAccountNo(no);
        a.setClient(c);
        a.setBranch(b);
        a.setOpenedDate(opened);
        a.setOpenedBy(req.getOpenedBy());
        a.setNotes(req.getNotes());
        accounts.saveAndFlush(a);

        if (fee.signum() > 0) {
            ReceiptRequest r = new ReceiptRequest();
            r.setBranchId(b.getId());
            r.setReceiptDate(opened);
            r.setType(ReceiptType.ACCOUNT_OPENING);
            r.setAccountId(a.getId());
            r.setAmount(fee);
            r.setReceiptNo(req.getOpeningReceiptNo());
            r.setPaymentMethod(req.getPaymentMethod());
            r.setCapturedBy(req.getOpenedBy());
            r.setSource(source);
            receiptService.record(r);
        }
        if (firstProject != null) {
            if (firstProject.getCreatedDate() == null) firstProject.setCreatedDate(opened);
            projectService.create(a.getId(), firstProject, source);
        }
        return a;
    }

    @Transactional
    public AssetAccount close(Long accountId, String reason) {
        AssetAccount a = get(accountId);
        boolean running = projectService.forAccount(accountId).stream().anyMatch(p -> p.getStatus().isOpen());
        if (running) throw new BusinessException("Complete or cancel the account's projects before closing it.");
        a.setClosed(true);
        a.setNotes(((a.getNotes() == null ? "" : a.getNotes() + "\n") + "Closed " + LocalDate.now() + ": " + (reason == null ? "" : reason)).trim());
        return accounts.save(a);
    }

    public AssetAccount get(Long id) {
        return accounts.findById(id).orElseThrow(() -> new BusinessException("Account not found."));
    }
}
