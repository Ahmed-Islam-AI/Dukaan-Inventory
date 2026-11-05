# PRD: Dukaan v1

## Problem
Small shopkeepers track stock in a register, or don't track it at all. At day's end they can't say
what sold, how much cash came in, how much profit they made, or which customers still owe money.
A mobile-phone shop has it worse: each phone is a unique unit (IMEI), and a wrong unit on a receipt
causes warranty and theft disputes.

Existing apps (Vyapar and others) solve this but are built around GST accounting: dozens of
reports and settings a small Pakistani shop never uses. They feel heavy at the counter.

## Product
An offline Android app that does four things fast:
1. **Stock in**: record what arrived, from whom, at what cost (with IMEIs for phones).
2. **Sell**: pick or scan items, take payment, issue a receipt. Stock goes down automatically.
3. **Know the numbers**: today's/month's collection, profit, stock value, who owes what.
4. **Never lose data**: automatic backup plus export file.

Generic: any shop type configures its own profile, categories, units, and variants.

## Personas
- **Owner** (primary, v1): runs the counter, 1 phone, Urdu or English, moderate smartphone skill.
  Wants speed at the counter and a trustworthy end-of-day number.
- **Staff** (future, with sync): sells but must not see profit or void sales.
- **Customer** (indirect): receives a receipt on WhatsApp or paper.

## Success criteria (first client, 4 weeks after M2 install)
- Every sale is recorded in the app (owner's paper register no longer needed).
- A sale with receipt takes under 20 seconds for a returning item.
- End-of-day collection in the app matches the cash drawer.
- No data loss incident.

## User stories and acceptance criteria

### Onboarding
- As an owner I set up my shop (name, type, phone, address, currency, logo, receipt prefix, language)
  in under 2 minutes. *AC:* only shop name is required; everything else has a default; I can change all of it later.

### Items
- I add an item with a sale price, category and unit, and choose **quantity** or **serial/IMEI** tracking.
- I add variants (e.g. 128GB / Blue) each with own price, barcode and stock.
- I see stock per item, with low-stock and negative-stock badges, and the full movement history.
- *AC:* items with no stock show 0, never blank; names up to 80 chars display without breaking layouts.

### Purchase / stock-in
- I record a purchase from a supplier: items, qty, unit cost, IMEIs (scan or type), amount paid.
- *AC:* duplicate IMEI already in stock is rejected with a clear message; an IMEI that was sold
  before can be bought back (trade-in) at a new cost (D-20).
- I can void a wrong purchase if nothing from it has been sold yet; otherwise I adjust stock with a reason (D-17).

### Sell
- I search or scan items into a cart, choose a customer (optional), apply discount/tax,
  enter amount paid and payment mode, and save.
- *AC:* selling a qty item with 0 stock warns but allows (D-14); selling an IMEI not in stock is blocked.
- *AC:* after save I land on the receipt with Share PDF, Print (thermal), Print A4.
- *AC:* if paid < total, the remainder is added to the customer's balance (customer required then).

### Receipts
- Receipt shows shop logo/name/phone/address, invoice number (`A-0001`), date, lines with
  qty × price and IMEI, subtotal, discount, tax, total, paid, balance, footer.
- *AC:* Urdu item names print correctly on thermal (raster, D-21).

### Corrections
- I void a wrong sale with a reason; stock and collection are restored; the receipt stays visible as VOID (D-17).
- I record a partial return; the refund reduces the customer's balance first, then is paid in cash (D-22).

### Customers and credit (udhaar)
- I see each customer's balance and statement, record a payment, and send a WhatsApp reminder text.

### Expenses
- I record rent, salaries, bills by category so profit reports show net profit.

### Reports and home
- Home: today's collection, profit, sale count; month toggle; low stock; cost-pending and
  negative-stock fix lists; backup reminder.
- Reports: sales, profit (gross and net of expenses), stock value, item-wise, credit due, expenses;
  date range filter; CSV export.

### Safety
- App lock with PIN or fingerprint.
- Automatic Android backup, plus manual export/import of a backup file.

## Non-goals (v1)
GST/FBR e-invoicing, double-entry accounting, multiple stores, online store, cloud sync, staff
accounts, subscriptions, iOS. See `FUTURE_FEATURES.md`.
