# ZimFete Asset Finance System (interim)

A small Spring Boot web app that replaces the Excel workbook (clients database, deposits sheet, daily income
sheet, monthly I&E) used by the asset finance office, until the Fineract loan system and the asset finance module
are live. It runs on the HQ computer; nothing needs internet.

**Enter a transaction once and every register and report updates.** Registering a member receipts the $10
joining fee and subscriptions and can open the $50 asset finance account (with its generated number) in the same
save. A deposit receipt updates the account total, flags the account when it reaches the 50% minimum deposit, and
appears in the daily report, the monthly I&E and the master deposit register.

## What it does

| Area | Screens |
|---|---|
| **Clerk (Murehwa / Macheke HQ)** | Register a client · Enrol a SACCO member · Open an account · Add projects · Receipt payments (joining fee $10, subscription $1/month, account opening fee, project deposit, loan repayment) · Printable receipt · Expenditure · Daily report with a WhatsApp copy-paste version |
| **AFM (all districts)** | Dashboard by district · Master register: accounts opened (per district, per clerk, inactive ones), deposits (who, how much, which project, when), projects (due, awaiting committee, approved, started, completed with dates and days taken), loan book and arrears, membership by veteran category, subscription arrears · Monthly I&E consolidated or per branch · Excel export of everything |
| **District returns** | Download an Excel return per branch. Clerks fill it offline and send it on WhatsApp. Upload it here and every row posts. Re-uploading the same file is safe because receipt numbers already on file are skipped. |

### Business rules built in
* **SACCO membership is for the veteran community only**: war veterans, war collaborators, ex-political prisoners/detainees/restrictees, widows and descendants of veterans. Members pay the $10 joining fee and $1 a month. The category is recorded so the grant eligibility list is ready when the grant comes.
* **Asset finance is open to everyone**, member or not, under company policy: open an account, deposit, then a loan.
* The **$50 opening fee can be paid in parts**. The account is *inactive* until it is fully paid (like the old "ACTIVE" column).
* **One account can carry several projects** (e.g. a borehole, later fencing). Deposits and repayments are posted to a project.
* Project lifecycle: Saving → Minimum deposit reached (automatic, 50% of cost by default) → **Approved by committee** → **Funds disbursed** (project started, loan fixed) → Completed. The committee can approve below the minimum if its reason is recorded.
* Loan = (project cost − deposited) + interest charged once, spread over the agreed months. The interest rate is per project. The default comes from `afs.default-interest-percent` (30%) and can be changed when funds are disbursed.
* Funds disbursed to suppliers appear as **outflow** ("Loan (project)") in the daily, monthly and WhatsApp reports, as in the old cashflow sheet. Fee income is reported separately from client deposits and repayments.
* A wrong receipt is **reversed**, not deleted: it stays visible but stops counting, and totals are recalculated.
* Numbers: accounts `MRE2641ME` (same style as the old register), clients `MRE-C00001`, receipts `MRE-R000001`. You can type your own receipt-book or account numbers instead. National ID is optional.

## Running it

Needs Java 17+ (and Maven to build).

```
cd asset-finance-system
mvn package               # builds target/asset-finance-system-1.0.0.jar and runs the tests
java -jar target/asset-finance-system-1.0.0.jar
```

Open http://localhost:8080 and sign in with `afm` / `zimfete2026`. **Change the password** in
`src/main/resources/application.properties` (`afs.login.password`), or start the app with
`--afs.login.password=...`. Set `afs.officer-name` to your name. It is stamped on receipts and accounts you capture.
`afs.default-interest-percent` sets the interest proposed for new projects.

Other PCs on the HQ network can use it at `http://<hq-pc-ip>:8080`.

## Data and backups

All data is in `data/afs.mv.db` next to where you start the jar. Back it up by copying the `data` folder while the
app is stopped, or click **Export master register (Excel)** on the dashboard. That gives one workbook with members,
accounts and projects, deposits, all receipts and expenditure. The export also makes it easy to migrate into
Fineract later.

## Moving from the old Excel workbook

To bring in historical data, put it in the district return format (download a template from **District
returns**), one file per branch, and upload it. Old members register from their first receipt row, accounts open
from `ACCOUNT_OPENING` rows (several rows for a part-paid fee), deposits post against the account number (with the
asset type when an account has more than one project), and a `JOINING_FEE` row with a Category enrols a SACCO member.
