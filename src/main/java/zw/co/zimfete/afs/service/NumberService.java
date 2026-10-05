package zw.co.zimfete.afs.service;

import java.time.LocalDate;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import zw.co.zimfete.afs.domain.Branch;
import zw.co.zimfete.afs.domain.NumberSequence;
import zw.co.zimfete.afs.repo.NumberSequenceRepository;

/**
 * Generates numbers per branch. Account numbers follow the existing register's style, e.g. MRE2641ME
 * (branch code, two-digit year, running number, "ME"). Numbers already taken, such as ones a district clerk
 * issued and we imported, are skipped.
 */
@Service
public class NumberService {
    private final NumberSequenceRepository sequences;

    public NumberService(NumberSequenceRepository sequences) {
        this.sequences = sequences;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String clientNo(Branch b, Predicate<String> taken) {
        return next("CLI-" + b.getCode(), n -> String.format("%s-C%05d", b.getCode(), n), taken);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String accountNo(Branch b, LocalDate opened, Predicate<String> taken) {
        String yy = String.format("%02d", opened.getYear() % 100);
        return next("ACC-" + b.getCode(), n -> String.format("%s%s%02dME", b.getCode(), yy, n), taken);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String receiptNo(Branch b, Predicate<String> taken) {
        return next("RCT-" + b.getCode(), n -> String.format("%s-R%06d", b.getCode(), n), taken);
    }

    private String next(String key, LongFunction<String> format, Predicate<String> taken) {
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
