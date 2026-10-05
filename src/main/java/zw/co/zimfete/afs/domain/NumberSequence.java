package zw.co.zimfete.afs.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Counter behind generated numbers, keyed e.g. "ACC-MRW" or "RCT-MRW". */
@Entity
public class NumberSequence {
    @Id
    private String name;
    private long nextValue;

    protected NumberSequence() {
    }

    public NumberSequence(String name, long nextValue) {
        this.name = name;
        this.nextValue = nextValue;
    }

    public String getName() { return name; }
    public long getNextValue() { return nextValue; }
    public void setNextValue(long nextValue) { this.nextValue = nextValue; }
}
