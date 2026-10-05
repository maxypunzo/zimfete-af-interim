package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.*;

/**
 * Records receipts and keeps the registers in step: one receipt updates the member's joining fee /
 * subscription status, the account's deposit and repayment totals, and the project status. The daily and
 * monthly income reports read straight from the receipts table, so nothing has to be entered twice.
 */
@Service
public class ReceiptService {
    private final ReceiptRepository receipts;
    private final MemberRepository members;
    private final AssetAccountRepository accounts;
    private final BranchRepository branches;
    private final NumberService numbers;
    private final AccountStatusService accountStatus;

    public ReceiptService(ReceiptRepository receipts, MemberRepository members, AssetAccountRepository accounts,
                          BranchRepository branches, NumberService numbers, AccountStatusService accountStatus) {
        this.receipts = receipts;
        this.members = members;
        this.accounts = accounts;
        this.branches = branches;
        this.numbers = numbers;
        this.accountStatus = accountStatus;
    }

    @Transactional
    public Receipt record(ReceiptRequest req) {
        if (req.getType() == null) throw new BusinessException("Choose what the receipt is for.");
        if (req.getAmount() == null || req.getAmount().signum() <= 0) throw new BusinessException("Amount must be greater than zero.");
        if (req.getReceiptDate() == null) req.setReceiptDate(LocalDate.now());
        if (req.getReceiptDate().isAfter(LocalDate.now())) throw new BusinessException("Receipt date cannot be in the future.");

        AssetAccount account = req.getAccountId() == null ? null
                : accounts.findById(req.getAccountId()).orElseThrow(() -> new BusinessException("Account not found."));
        Member member = req.getMemberId() == null ? null
                : members.findById(req.getMemberId()).orElseThrow(() -> new BusinessException("Member not found."));
        if (account != null) member = account.getMember();

        if (req.getType().needsAccount() && account == null) {
            throw new BusinessException(req.getType().getLabel() + " must be posted to an asset finance account.");
        }
        if (req.getType() == ReceiptType.ACCOUNT_OPENING && account == null) {
            throw new BusinessException("Use \"Open account\" on the member's page: it generates the account number and receipts the fee.");
        }
        if (req.getType() == ReceiptType.LOAN_REPAYMENT && !account.isLoanStarted()) {
            throw new BusinessException("Account " + account.getAccountNo() + " has no running loan yet. Post this as a deposit, or start the project first.");
        }
        if (req.getType() == ReceiptType.ASSET_DEPOSIT && account.isLoanStarted()) {
            throw new BusinessException("The project on " + account.getAccountNo() + " has started; payments now go in as loan repayments.");
        }
        if (account != null && account.getStatus() == ProjectStatus.CANCELLED) {
            throw new BusinessException("Account " + account.getAccountNo() + " is cancelled.");
        }
        if (req.getType() != ReceiptType.OTHER_INCOME && member == null) {
            throw new BusinessException("Choose the member who paid.");
        }

        Branch branch = req.getBranchId() != null ? branches.findById(req.getBranchId()).orElseThrow()
                : account != null ? account.getBranch() : member != null ? member.getBranch() : null;
        if (branch == null) throw new BusinessException("Choose a branch.");

        Integer months = req.getMonths();
        if (req.getType() == ReceiptType.SUBSCRIPTION && (months == null || months <= 0)) {
            // $1 a month: default the months covered from the amount paid
            months = Math.max(1, req.getAmount().divide(ReceiptType.SUBSCRIPTION.getStandardAmount(), 0, java.math.RoundingMode.DOWN).intValue());
        }

        String no = req.getReceiptNo() == null ? "" : req.getReceiptNo().trim();
        final Long branchId = branch.getId();
        if (no.isEmpty()) {
            no = numbers.receiptNo(branch, n -> receipts.existsByBranchIdAndReceiptNoIgnoreCase(branchId, n));
        } else if (receipts.existsByBranchIdAndReceiptNoIgnoreCase(branchId, no)) {
            throw new BusinessException("Receipt number " + no + " already exists for " + branch.getName() + ".");
        }

        Receipt r = new Receipt();
        r.setReceiptNo(no);
        r.setReceiptDate(req.getReceiptDate());
        r.setBranch(branch);
        r.setMember(member);
        r.setAccount(account);
        r.setType(req.getType());
        r.setAmount(req.getAmount().setScale(2, java.math.RoundingMode.HALF_UP));
        r.setMonths(req.getType() == ReceiptType.SUBSCRIPTION ? months : null);
        r.setPaymentMethod(blankToNull(req.getPaymentMethod()));
        r.setReference(blankToNull(req.getReference()));
        r.setCapturedBy(blankToNull(req.getCapturedBy()));
        r.setDescription(blankToNull(req.getDescription()));
        r.setSource(req.getSource());
        receipts.saveAndFlush(r);

        applyEffects(r);
        return r;
    }

    /** Reverses (cancels) a receipt captured in error. The row stays for audit but no longer counts anywhere. */
    @Transactional
    public Receipt reverse(Long receiptId, String reason) {
        Receipt r = receipts.findById(receiptId).orElseThrow(() -> new BusinessException("Receipt not found."));
        if (r.isReversed()) return r;
        if (r.getType() == ReceiptType.ACCOUNT_OPENING && r.getAccount() != null
                && r.getAccount().getStatus() != ProjectStatus.CANCELLED) {
            throw new BusinessException("Cancel account " + r.getAccount().getAccountNo() + " before reversing its opening fee.");
        }
        r.setReversed(true);
        r.setDescription(((r.getDescription() == null ? "" : r.getDescription() + " | ") + "REVERSED: " + (reason == null ? "" : reason)).trim());
        receipts.saveAndFlush(r);
        applyEffects(r);
        return r;
    }

    private void applyEffects(Receipt r) {
        if (r.getMember() != null) recalcMember(r.getMember());
        if (r.getAccount() != null) accountStatus.recalc(r.getAccount());
    }

    /** Joining fee flag and subscription "paid until" month are derived from the member's receipts. */
    public void recalcMember(Member m) {
        m.setJoiningFeePaid(receipts.countJoiningFees(m.getId()) > 0);
        long months = receipts.sumSubscriptionMonths(m.getId());
        m.setSubsPaidUntil(months > 0 ? m.getDateJoined().withDayOfMonth(1).plusMonths(months - 1) : null);
        members.save(m);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static BigDecimal orZero(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}
