package zw.co.zimfete.afs.domain;

import jakarta.persistence.*;

/** One of the seven ZimFete branches. The branch code prefixes every account, member and receipt number. */
@Entity
public class Branch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 5)
    private String code;

    @Column(nullable = false)
    private String name;

    /** District served by the branch (Macheke HQ serves Murehwa district). */
    @Column(nullable = false)
    private String district;

    private boolean headOffice;

    /**
     * False for a location kept only for historical records (e.g. Harare in the old register). Such a location
     * is not offered when capturing; new clients from outside our branches are recorded under the branch that
     * assisted them, with their own district/location on the client record.
     */
    private Boolean operating = Boolean.TRUE;

    /** Name of the asset finance clerk at this branch. */
    private String clerkName;

    protected Branch() {
    }

    public Branch(String code, String name, String district, boolean headOffice) {
        this.code = code;
        this.name = name;
        this.district = district;
        this.headOffice = headOffice;
    }

    public static Branch historical(String code, String name, String district) {
        Branch b = new Branch(code, name, district, false);
        b.operating = Boolean.FALSE;
        return b;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDistrict() { return district; }
    public boolean isHeadOffice() { return headOffice; }
    public boolean isOperating() { return operating == null || operating; }
    public String getClerkName() { return clerkName; }
    public void setClerkName(String clerkName) { this.clerkName = clerkName; }

    public String getLabel() {
        return headOffice ? name + " (HQ)" : isOperating() ? name : name + " (no branch, historical)";
    }
}
