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
* Loan = (project cost − deposited) + interest charged once, spread over the agreed months. **Company policy is 30%** (`afs.default-interest-percent`). A different rate for a project is a committee decision: the system will not disburse at another rate unless the committee decision is recorded.
* Clients from outside our branch areas (e.g. Harare) are registered under the branch that assisted them, with their own district/town in *District / location*. Harare appears only as a historical location for the old record (Foroma).
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

## District clerks' workbook

Each clerk gets their own branch's workbook from **District returns → Download template**. They fill it offline (Excel or
WPS on a phone) and send it on the WhatsApp group. You upload it on the same page and every row posts.

* **Receipts** and **Expenditure**: one row per receipt / payment out, with dropdowns for type, category, gender, asset
  type and payment method. A *District / Location* column records clients from outside our branch areas.
* **Summary**: the clerk types a From and To date and gets their daily, weekly or monthly totals (by receipt type,
  expenditure, net cash) for the WhatsApp report, worked out from what they typed.
* **Accounts**: the branch's accounts and projects, so clerks quote the right account number.
* The branch code is stored in the file, so a return uploaded under the wrong branch is refused. Receipt numbers
  already captured are skipped, so the clerk can keep adding to the same file and resend it.

## Importing the previous AFM's workbook (one-off)

**District returns → One-off: bring in the previous AFM's workbook.** Choose her .xlsx and the date the balances are
"as at". It runs once and:

* takes the **client database** (plus *Incomplete records*) as the master register: clients, accounts, one project per
  row, opening fees and amounts deposited. The amounts come in as balances brought forward ("B/F old register"); they
  are not cash received in this system, so they are left out of the cash reports;
* takes project costs, recorded loans (total loan, grand total, instalment, amount paid) and committee approvals from
  *Deposits*, *Repayments* and *Project approval* only where the row clearly matches a client and the sheets agree;
* brings the *daily inflow* sheets, the airtime line and the August project payouts in as an old cash book, so the
  monthly reports for those months match her sheets (payer names as written, not linked to clients);
* never corrects or guesses a value: blanks stay blank (dates, IDs, categories, account numbers), and every
  disagreement between sheets is listed in **the list to confirm**, which you can download as Excel.

Decisions already applied: Pefenia Kahuni's account is MRE2614ME (MRE2622ME was a duplicate); MRE2627ME is Mercy
Mukuzo's; anyone on *Incomplete records* whose number is already used in the client database gets a new number.

## Moving other records in

To bring in historical data, put it in the district return format (download a template from **District
returns**), one file per branch, and upload it. Old members register from their first receipt row, accounts open
from `ACCOUNT_OPENING` rows (several rows for a part-paid fee), deposits post against the account number (with the
asset type when an account has more than one project), and a `JOINING_FEE` row with a Category enrols a SACCO member.
