# Dukaan (working name)

Offline inventory and billing for any small shop: add stock, sell with one tap, give the customer
a receipt (WhatsApp PDF, Bluetooth thermal, or A4), and see today's collection and profit.
Built for Android first, for a mobile-phone shop (IMEI tracking) and generic enough for any shop type.

## Build
Requirements: Android SDK (compileSdk 37), JDK 17+.
```bash
./gradlew assembleDebug        # build
./gradlew testDebugUnitTest    # tests
./gradlew installDebug         # install on a device
```

## Docs
| Doc | What's in it |
|-----|--------------|
| [CLAUDE.md](CLAUDE.md) | Rules and commands for anyone (human or agent) changing the code |
| [docs/PRD.md](docs/PRD.md) | What we're building and for whom; user stories |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Data model, transactions, FIFO, invariants |
| [docs/DESIGN.md](docs/DESIGN.md) | Design system, screens, flows, receipt layout |
| [docs/PLAN.md](docs/PLAN.md) | Milestones M0 to M6 with checklists |
| [docs/PROGRESS.md](docs/PROGRESS.md) | Current status |
| [CHANGELOG.md](CHANGELOG.md) | What changed, per version |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Every product/tech decision and why |
| [docs/TESTING.md](docs/TESTING.md) | Test strategy and required cases |
| [docs/RELEASE.md](docs/RELEASE.md) | Signing, Play Store, Crashlytics, Data safety, listing, migrations |
| [docs/PRIVACY.md](docs/PRIVACY.md) | Privacy policy draft (owner hosts it) |
| [docs/ACCOUNTS.md](docs/ACCOUNTS.md) | Accounts, staff, cloud copy (M7): design, limits, Firestore layout |
| [docs/SYNC.md](docs/SYNC.md) | Live sync between a shop's phones: how it merges, clashes, cost |
| [docs/FUTURE_FEATURES.md](docs/FUTURE_FEATURES.md) | Backlog of deferred features |
