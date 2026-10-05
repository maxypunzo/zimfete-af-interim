package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.AssetAccountRepository;
import zw.co.zimfete.afs.repo.MemberRepository;

/** Opening asset finance accounts and moving projects through their lifecycle. */
@Service
public class AccountService {
    private final AssetAccountRepository accounts;
    private final MemberRepository members;
    private final NumberService numbers;
    private final ReceiptService receiptService;
    private final AccountStatusService accountStatus;

    public AccountService(AssetAccountRepository accounts, MemberRepository members, NumberService numbers,
                          ReceiptService receiptService, AccountStatusService accountStatus) {
        this.accounts = accounts;
        this.members = members;
        this.numbers = numbers;
        this.receiptService = receiptService;
        this.accountStatus = accountStatus;
    }

    /**
     * Opens an account for a member: generates (or accepts) the account number, receipts the $50 opening
     * fee and, if given, the first deposit.
     */
    @Transactional
    public AssetAccount open(Long memberId, AccountRequest req, String source) {
        Member m = members.findById(memberId).orElseThrow(() -> new BusinessException("Member not found."));
        Branch b = m.getBranch();
        LocalDate opened = req.getOpenedDate() != null ? req.getOpenedDate() : LocalDate.now();

        String no = req.getAccountNo() == null ? "" : req.getAccountNo().trim().toUpperCase();
        if (no.isEmpty()) {
            no = numbers.accountNo(b, accounts::existsByAccountNoIgnoreCase);
        } else if (accounts.existsByAccountNoIgnoreCase(no)) {
            throw new BusinessException("Account number " + no + " is already in use.");
        }

        AssetAccount a = new AssetAccount();
        a.setAccountNo(no);
        a.setMember(m);
        a.setBranch(b);
        a.setOpenedDate(opened);
        a.setOpenedBy(req.getOpenedBy());
        copyDetails(req, a);
        accounts.saveAndFlush(a);

        ReceiptRequest fee = new ReceiptRequest();
        fee.setBranchId(b.getId());
        fee.setReceiptDate(opened);
        fee.setType(ReceiptType.ACCOUNT_OPENING);
        fee.setMemberId(m.getId());
        fee.setAccountId(a.getId());
        fee.setAmount(ReceiptType.ACCOUNT_OPENING.getStandardAmount());
        fee.setReceiptNo(req.getOpeningReceiptNo());
        fee.setPaymentMethod(req.getPaymentMethod());
        fee.setCapturedBy(req.getOpenedBy());
        fee.setSource(source);
        receiptService.record(fee);

        if (req.getInitialDeposit() != null && req.getInitialDeposit().signum() > 0) {
            ReceiptRequest dep = new ReceiptRequest();
            dep.setBranchId(b.getId());
            dep.setReceiptDate(opened);
            dep.setType(ReceiptType.ASSET_DEPOSIT);
            dep.setAccountId(a.getId());
            dep.setAmount(req.getInitialDeposit());
            dep.setReceiptNo(req.getDepositReceiptNo());
            dep.setPaymentMethod(req.getPaymentMethod());
            dep.setCapturedBy(req.getOpenedBy());
            dep.setSource(source);
            receiptService.record(dep);
        }
        return a;
    }

    @Transactional
    public AssetAccount updateDetails(Long accountId, AccountRequest req) {
        AssetAccount a = get(accountId);
        if (a.isLoanStarted() && req.getQuotationCost() != null && a.getQuotationCost() != null
                && req.getQuotationCost().compareTo(a.getQuotationCost()) != 0) {
            throw new BusinessException("The loan has already been calculated; the quotation can no longer change.");
        }
        if (req.getOpenedBy() != null && !req.getOpenedBy().isBlank()) a.setOpenedBy(req.getOpenedBy());
        copyDetails(req, a);
        accountStatus.recalc(a);
        return a;
    }

    /** Starts the project: ZimFete finances the balance; the loan (principal + 30%) is fixed from here on. */
    @Transactional
    public AssetAccount startProject(Long accountId, LocalDate startDate, Integer repaymentMonths) {
        AssetAccount a = get(accountId);
        if (a.getStatus() != ProjectStatus.THRESHOLD_MET) {
            throw new BusinessException("Only accounts that have reached the minimum deposit can start. "
                    + "Shortfall: $" + ReceiptService.orZero(a.getDepositShortfall()));
        }
        if (repaymentMonths == null || repaymentMonths <= 0) throw new BusinessException("Enter the agreed repayment period in months.");
        a.setRepaymentMonths(repaymentMonths);
        a.setProjectStartDate(startDate != null ? startDate : LocalDate.now());
        LoanTerms t = LoanTerms.calculate(a.getQuotationCost(), a.getTotalDeposited(), repaymentMonths);
        a.setLoanPrincipal(t.principal());
        a.setLoanInterest(t.interest());
        a.setStatus(ProjectStatus.IN_PROGRESS);
        accountStatus.recalc(a);
        return a;
    }

    /** Marks the asset as delivered / installed. Loan repayments continue until the balance is cleared. */
    @Transactional
    public AssetAccount complete(Long accountId, LocalDate completionDate) {
        AssetAccount a = get(accountId);
        if (a.getStatus() != ProjectStatus.IN_PROGRESS) throw new BusinessException("Only started projects can be completed.");
        LocalDate d = completionDate != null ? completionDate : LocalDate.now();
        if (d.isBefore(a.getProjectStartDate())) throw new BusinessException("Completion date is before the start date.");
        a.setCompletionDate(d);
        a.setStatus(ProjectStatus.COMPLETED);
        accounts.save(a);
        return a;
    }

    @Transactional
    public AssetAccount cancel(Long accountId, String reason) {
        AssetAccount a = get(accountId);
        if (a.isLoanStarted()) throw new BusinessException("A project with a running loan cannot be cancelled.");
        a.setStatus(ProjectStatus.CANCELLED);
        a.setNotes(((a.getNotes() == null ? "" : a.getNotes() + "\n") + "Cancelled " + LocalDate.now() + ": " + (reason == null ? "" : reason)).trim());
        accounts.save(a);
        return a;
    }

    public AssetAccount get(Long id) {
        return accounts.findById(id).orElseThrow(() -> new BusinessException("Account not found."));
    }

    private static void copyDetails(AccountRequest req, AssetAccount a) {
        if (req.getQuotationCost() != null && req.getQuotationCost().signum() < 0) throw new BusinessException("Quotation cost cannot be negative.");
        a.setAssetType(req.getAssetType());
        a.setAssetDescription(req.getAssetDescription());
        a.setSupplier(req.getSupplier());
        if (!a.isLoanStarted()) a.setQuotationCost(req.getQuotationCost());
        a.setMinDepositPercent(req.getMinDepositPercent() != null ? req.getMinDepositPercent() : BigDecimal.valueOf(50));
        if (!a.isLoanStarted()) a.setRepaymentMonths(req.getRepaymentMonths());
        a.setTargetDate(req.getTargetDate());
        a.setNotes(req.getNotes());
    }
}
