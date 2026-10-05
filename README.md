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
| **Clerk (Murehwa / Macheke HQ)** | Register member · Open account · Receipt payment (joining fee $10, subscription $1/month, account opening $50, asset deposit, loan repayment) · Printable receipt · Expenditure · Daily report with a WhatsApp copy-paste version |
| **AFM (all districts)** | Dashboard by district · Master register: accounts opened (per district, per clerk), deposits (who, how much, which asset, when), projects (due, minimum reached, started, completed with dates and days taken), loan book and arrears, subscription arrears · Monthly I&E consolidated or per branch · Excel export of everything |
| **District returns** | Download an Excel return per branch. Clerks fill it offline and send it on WhatsApp. Upload it here and every row posts. Re-uploading the same file is safe because receipt numbers already on file are skipped. |

### Business rules built in
* Minimum deposit = 50% of quotation (editable per account).
* Loan = (quotation − deposited) + 30% once, spread evenly over the agreed months. The figures are fixed when you press **Start project**.
* Statuses: Saving → Minimum deposit reached (automatic) → Project started → Completed. Cancelled is also available.
* "Due" means the client's target start date is within 14 days or has passed, and the project hasn't started.
* Income (fees) is reported separately from client funds (deposits and repayments).
* A wrong receipt is **reversed**, not deleted: it stays visible but stops counting, and totals are recalculated.
* Numbers: members `MRW-M00001`, accounts `AF-MRW-0001`, receipts `MRW-R000001`. You can type your own receipt-book or account numbers instead.

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

Other PCs on the HQ network can use it at `http://<hq-pc-ip>:8080`.

## Data and backups

All data is in `data/afs.mv.db` next to where you start the jar. Back it up by copying the `data` folder while the
app is stopped, or click **Export master register (Excel)** on the dashboard. That gives one workbook with members,
accounts and projects, deposits, all receipts and expenditure. The export also makes it easy to migrate into
Fineract later.

## Moving from the old Excel workbook

To bring in historical data, put it in the district return format (download a template from **District
returns**), one file per branch, and upload it. Old members register from their first receipt row, accounts open
from `ACCOUNT_OPENING` rows, and deposits post against the account numbers.
