package zw.co.zimfete.afs.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.zimfete.afs.domain.*;
import zw.co.zimfete.afs.repo.*;

/**
 * Records receipts and keeps the registers in step: one receipt updates the client's membership fees, the
 * account's opening fee, the project's deposits or loan, and the reports (which read the receipts table).
 */
@Service
public class ReceiptService {
    private final ReceiptRepository receipts;
    private final ClientRepository clients;
    private final AssetAccountRepository accounts;
    private final ProjectRepository projects;
    private final BranchRepository branches;
    private final NumberService numbers;
    private final StatusService status;

    public ReceiptService(ReceiptRepository receipts, ClientRepository clients, AssetAccountRepository accounts,
                          ProjectRepository projects, BranchRepository branches, NumberService numbers, StatusService status) {
        this.receipts = receipts;
        this.clients = clients;
        this.accounts = accounts;
        this.projects = projects;
        this.branches = branches;
        this.numbers = numbers;
        this.status = status;
    }

    @Transactional
    public Receipt record(ReceiptRequest req) {
        ReceiptType type = req.getType();
        if (type == null) throw new BusinessException("Choose what the receipt is for.");
        if (req.getAmount() == null || req.getAmount().signum() <= 0) throw new BusinessException("Amount must be greater than zero.");
        if (req.getReceiptDate() == null) req.setReceiptDate(LocalDate.now());
        if (req.getReceiptDate().isAfter(LocalDate.now())) throw new BusinessException("Receipt date cannot be in the future.");

        Project project = req.getProjectId() == null ? null
                : projects.findById(req.getProjectId()).orElseThrow(() -> new BusinessException("Project not found."));
        AssetAccount account = project != null ? project.getAccount() : req.getAccountId() == null ? null
                : accounts.findById(req.getAccountId()).orElseThrow(() -> new BusinessException("Account not found."));
        Client client = account != null ? account.getClient() : req.getClientId() == null ? null
                : clients.findById(req.getClientId()).orElseThrow(() -> new BusinessException("Client not found."));

        if (type.needsProject() && project == null && account != null) project = onlyProject(account, type);
        if (type.needsProject() && project == null) {
            throw new BusinessException(type.getLabel() + " must be posted to a project on an asset finance account.");
        }
        if (type != ReceiptType.OTHER_INCOME && client == null) throw new BusinessException("Choose the client who paid.");

        switch (type) {
            case JOINING_FEE, SUBSCRIPTION -> {
                if (!client.isSaccoMember()) {
                    throw new BusinessException(client.getFullName() + " is not a SACCO member. Joining fees and subscriptions are for "
                            + "members from the veteran community: enrol them as a member first.");
                }
                if (type == ReceiptType.JOINING_FEE && client.isJoiningFeePaid()) {
                    throw new BusinessException(client.getFullName() + " has already paid the joining fee.");
                }
            }
            case ACCOUNT_OPENING -> {
                if (account == null) throw new BusinessException("Choose the account the opening fee is for, or open a new account from the client's page.");
                if (account.getOpeningFeeBalance().signum() == 0) throw new BusinessException("The opening fee for " + account.getAccountNo() + " is already fully paid.");
                if (req.getAmount().compareTo(account.getOpeningFeeBalance()) > 0) {
                    throw new BusinessException("Only $" + account.getOpeningFeeBalance() + " of the opening fee is outstanding on " + account.getAccountNo() + ".");
                }
            }
            case ASSET_DEPOSIT -> {
                if (!project.getStatus().isPreStart()) {
                    throw new BusinessException(project.getStatus() == ProjectStatus.CANCELLED ? "That project is cancelled."
                            : "The project " + project.getLabel() + " has started; payments now go in as loan repayments.");
                }
            }
            case LOAN_REPAYMENT -> {
                if (!project.isLoanStarted()) {
                    throw new BusinessException(project.getLabel() + " has no running loan yet. Post this as a deposit, or start the project first.");
                }
            }
            default -> { }
        }
        if (account != null && account.isClosed()) throw new BusinessException("Account " + account.getAccountNo() + " is closed.");

        Branch branch = req.getBranchId() != null ? branches.findById(req.getBranchId()).orElseThrow()
                : account != null ? account.getBranch() : client != null ? client.getBranch() : null;
        if (branch == null) throw new BusinessException("Choose a branch.");

        Integer months = req.getMonths();
        if (type == ReceiptType.SUBSCRIPTION && (months == null || months <= 0)) {
            months = Math.max(1, req.getAmount().divide(ReceiptType.SUBSCRIPTION.getStandardAmount(), 0, RoundingMode.DOWN).intValue());
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
        r.setClient(client);
        r.setAccount(account);
        r.setProject(type.needsProject() ? project : null);
        r.setType(type);
        r.setAmount(req.getAmount().setScale(2, RoundingMode.HALF_UP));
        r.setMonths(type == ReceiptType.SUBSCRIPTION ? months : null);
        r.setPaymentMethod(blankToNull(req.getPaymentMethod()));
        r.setReference(blankToNull(req.getReference()));
        r.setCapturedBy(blankToNull(req.getCapturedBy()));
        r.setDescription(blankToNull(req.getDescription()));
        r.setSource(req.getSource());
        receipts.saveAndFlush(r);

        applyEffects(r);
        return r;
    }

    /** Reverses a receipt captured in error. The row stays for audit but no longer counts anywhere. */
    @Transactional
    public Receipt reverse(Long receiptId, String reason) {
        Receipt r = receipts.findById(receiptId).orElseThrow(() -> new BusinessException("Receipt not found."));
        if (r.isReversed()) return r;
        r.setReversed(true);
        r.setDescription(((r.getDescription() == null ? "" : r.getDescription() + " | ") + "REVERSED: " + (reason == null ? "" : reason)).trim());
        receipts.saveAndFlush(r);
        applyEffects(r);
        return r;
    }

    private void applyEffects(Receipt r) {
        if (r.getClient() != null) status.recalcClient(r.getClient());
        if (r.getAccount() != null) status.recalcAccount(r.getAccount());
        if (r.getProject() != null) status.recalcProject(r.getProject());
    }

    /** When only the account is given, use its single eligible project. */
    private Project onlyProject(AssetAccount account, ReceiptType type) {
        List<Project> eligible = projects.findByAccountIdOrderByIdAsc(account.getId()).stream()
                .filter(p -> type == ReceiptType.LOAN_REPAYMENT ? p.isLoanStarted() : p.getStatus().isPreStart()).toList();
        if (eligible.size() == 1) return eligible.get(0);
        if (eligible.isEmpty()) {
            throw new BusinessException("Account " + account.getAccountNo() + " has no project "
                    + (type == ReceiptType.LOAN_REPAYMENT ? "with a running loan." : "taking deposits. Add the project (asset) first."));
        }
        throw new BusinessException("Account " + account.getAccountNo() + " has " + eligible.size() + " projects: choose which one.");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static BigDecimal orZero(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}
