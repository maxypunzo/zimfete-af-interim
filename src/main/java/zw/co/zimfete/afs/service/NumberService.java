package zw.co.zimfete.afs.service;

import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import zw.co.zimfete.afs.domain.Branch;
import zw.co.zimfete.afs.domain.NumberSequence;
import zw.co.zimfete.afs.repo.NumberSequenceRepository;

/**
 * Generates member, account and receipt numbers per branch, e.g. MRW-M00012, AF-MRW-0007, MRW-R000123.
 * Numbers already taken (for example account numbers a district clerk issued and we imported) are skipped.
 */
@Service
public class NumberService {
    private final NumberSequenceRepository sequences;

    public NumberService(NumberSequenceRepository sequences) {
        this.sequences = sequences;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String memberNo(Branch b, Predicate<String> taken) {
        return next("MEM-" + b.getCode(), n -> String.format("%s-M%05d", b.getCode(), n), taken);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String accountNo(Branch b, Predicate<String> taken) {
        return next("ACC-" + b.getCode(), n -> String.format("AF-%s-%04d", b.getCode(), n), taken);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String receiptNo(Branch b, Predicate<String> taken) {
        return next("RCT-" + b.getCode(), n -> String.format("%s-R%06d", b.getCode(), n), taken);
    }

    private String next(String key, java.util.function.LongFunction<String> format, Predicate<String> taken) {
        NumberSequence seq = sequences.lock(key).orElseGet(() -> sequences.saveAndFlush(new NumberSequence(key, 1)));
        long n = seq.getNextValue();
        String candidate = format.apply(n);
        while (taken.test(candidate)) {
            n++;
            candidate = format.apply(n);
        }
        seq.setNextValue(n + 1);
        return candidate;
    }
}
