# Accounts, staff and cloud (M7)

Owner decisions 2026-10-15 (Q&A in chat): accounts exist to **control who uses the app**, **keep
data safe in the cloud** and **run one shop on several phones**. Firebase, free (Spark) plan first.
Delivered in three phases; each is usable on its own.

| Phase | Scope |
|---|---|
| **1** | Animations · sign up / log in (Google, email + password, phone + password) · account checks with offline grace · cloud copy of the shop (upload existing data, restore on a new phone) · log out wipes the phone · block from Firebase console |
| **2** | Staff: owner adds a staff member's phone/email; they join on sign-up. Staff may sell, take payments, add customers, add stock/items, return/void, see cost/profit/reports. Owner-only: shop settings, staff management |
| **3** | Live sync between the shop's phones (docs/SYNC.md); same IMEI sold offline on two phones → both bills kept + "Problems between phones" for the owner |

## Free-plan limits (verify on firebase.google.com/pricing)
- Auth: email/password and Google free. **SMS code login needs the paid Blaze plan** (+ per-SMS cost): added later.
- Firestore free per day, whole project: 50k reads, 20k writes, 1 GB stored. A cloud copy is a few
  writes. Live sync writes **one document per push** (a sale, or several changes made within 1.5 s),
  and each other phone of the shop reads it once. A shop with 3 phones and 300 changes a day uses
  about 300 writes + 600 reads, so the free plan carries roughly 60 such shops; past that, Blaze
  costs cents per shop per month (docs/SYNC.md § Cost).
- Cloud Storage for new projects needs Blaze, so the cloud copy is stored **inside Firestore** as
  compressed chunks (< 1 MiB per document).
- No server code on Spark: everything is client + security rules (`firestore.rules`).

## Sign-in methods
- **Google**: Credential Manager + Google ID token → Firebase. Needs the web OAuth client in
  `google-services.json` (Google provider enabled + SHA-1 added before downloading the file). The
  button is hidden when the client id is missing.
- **Email + password**: Firebase email/password; "Forgot password" sends Firebase's reset email.
- **Phone + password** (no SMS on Spark): the number is normalised (`PhoneNumbers`, Pakistan default:
  0300… → 92300…) and the account's login email is the user's real email if given, otherwise a hidden
  `<digits>@phone.<project>.firebaseapp.com`. `phoneLogins/{digits}` maps the number to that email so
  the login screen can sign in with phone + password.
  - Trade-off (accepted): anyone who knows a shop's number can read the login email behind it.
  - Not proven by SMS: anyone can register a number they don't own; first come, first served.
  - Reset: with a real email, Firebase reset email. Without one, the owner of the app resets it with
    `tools/reset-password` (Admin SDK on the owner's PC, free).

## Firestore layout
```
users/{uid}            name, phone, email?, shopId, role (owner|staff), createdAt, updatedAt
shops/{shopId}         name, type, address?, phone?, currency, ownerUid, status (active|blocked), createdAt
shops/{shopId}/backup/meta          schemaVersion, chunks, bytes, sha256, uploadedAt, deviceId
shops/{shopId}/backupChunks/{n}     data (Blob, gzip of the WAL-safe snapshot)
phoneLogins/{digits}   email, uid       (phone + password accounts only)
invites/{key}          shopId, shopName, role=staff, name, invitedBy, createdAt
                       key = lower-case email, or phone digits (92300…)
shops/{shopId}/changes/{auto}       by (deviceId), uid, at (server time), n (rows), v (schema), data
                                    (Blob: gzip of the rows one phone pushed; Phase 3, append-only)
```
Rules: a user reads/writes only their own `users` doc; shop docs, its copy and its change log only
by its active members (the copy is written by the owner; changes are only ever added, stamped with
the server's time); `phoneLogins` allows single-document `get` to anyone, never `list`; `status` can
only be changed from the console.

## Staff (Phase 2)
- Owner: More → Staff → Add staff (name + phone or email). Saved as `invites/{key}`; a second invite
  for the same key is refused ("already invited").
- The invited person creates an account with that phone or email; after sign-in the app finds the
  invite (by their email, or by the phone they signed up with) and asks "Join <shop> as staff?".
  Join = their `users` doc with role `staff` + delete the invite, in one batch; then the shop's
  cloud copy is restored on their phone and live sync brings everything since. "Create my own shop
  instead" leaves the invite in place.
- Staff can sell, take payments, add customers and stock, return/void and see profit. Owner-only:
  Staff, Backup & restore (a restore replaces the whole shop). There is no shop-settings screen yet
  (details come from sign-up); when one is added it is owner-only.
- Remove: the owner deletes the staff `users` doc. That staff phone's next check (REMOVED) wipes it
  and shows "You were removed from <shop>".
- Phase 2 gave each staff member a private cloud copy until sync existed; Phase 3 replaced it with
  live sync (D-79). Old `staffCopies` documents are unused and can be deleted.

## Blocking: only on explicit signals
The account check wipes the phone, so it blocks **only** when the server clearly says so: the user is
disabled or deleted in the console (`ERROR_USER_DISABLED` / `ERROR_USER_NOT_FOUND`) or the shop's
`status` reads `blocked`, or (Phase 2) the user's own `users` doc is gone or points at another shop
(removed by the owner). Network errors, permission hiccups or a token not ready yet count as
"couldn't check" (offline grace). Found on the emulator 2026-10-15: an ambiguous error right after a
cloud restore was treated as a block and wiped the phone; fixed before release.

Firestore runs with a **memory-only cache**, so no cloud copy is left on disk after log out; any
older `firestore.*` cache files are deleted by the wipe.

## Account states (app)
`SignedOut` → Welcome · `NeedsProfile` (signed in, no shop yet) → shop details ·
`Ready` · `Blocked` → wipe + blocked screen · `NeedsInternet` → "Connect to continue" (data kept).

- Verified = `user.reload()` + read `shops/{id}.status`, when online at start and on return to the app
  (at most every 6 h). Disabled user, deleted user or `status = blocked` → Blocked.
- Offline grace: **14 days** since the last successful check, then NeedsInternet.
- Block an account: Firebase console → Authentication → user → **Disable** (or set the shop's
  `status` to `blocked` in Firestore).

## Cloud copy (Phase 1)
- A new account always starts empty (D-82): shop data still on the phone is removed once the account
  exists (Welcome says so). To bring old data into an account: export it (More → Backup) before the
  phone gets the accounts version, sign up, then More → Backup → import that file.
- After that: uploaded when leaving the app if changed since the last upload (≥ 15 min apart).
- New phone / reinstall: after login, the latest copy is downloaded and restored (existing import path).
- Log out: upload first (refused offline, so nothing is lost), then sign out and delete the shop data
  and device settings from the phone.
- Phase 3: the copy is only the starting point for a new phone (restored, then live sync pulls the
  changes since). The owner keeps it fresh; staff never upload one.

## Live sync (Phase 3)
How it works and why: docs/SYNC.md. In short:
- Every change on a phone is queued (SQLite triggers), pushed within ~1.5 s as one compressed
  document, and applied on the shop's other phones by a live listener while the app is open.
- Offline: changes wait on the phone and go up when it is back online. Two phones working offline
  merge when they reconnect: same row edited twice → the later edit wins; stock and costs are
  recomputed from the merged facts, so every phone shows the same numbers.
- What can't both be true (same IMEI sold twice, a bill voided on one phone and returned on another,
  stock taken out twice, two phones using one bill letter) is kept and listed for the owner on Home
  → "Problems between phones", with the bills to open and fix.
- Bill letters: each phone needs its own (Sell refuses a letter another phone already prints).
- Log out: refused until every change has reached the cloud. A saved "sign-out pending" marker makes
  the next start sign out again, because Firebase saves its sign-out lazily and the app restarts
  right after the wipe.
- Rules: `firestore.rules` must be published again whenever it changes (Phase 3 added `changes`);
  until then sync shows "Waiting to upload" and nothing is lost.
- Account → "Live sync" shows whether anything is still waiting to upload.
