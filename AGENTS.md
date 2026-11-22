# Working on this repository

## Fast feedback

- `./gradlew :core:test :world:test :eval:test` runs in under a minute; `./gradlew build` adds the service tests, which
  need Docker for MySQL.
- `./gradlew :eval:evaluate :eval:gate :eval:readme` runs the three development seeds (under a minute) and regenerates
  REPORT.md and the README block.
- `cd web && npm test && npm run typecheck && npm run lint` for the dashboard.
- `cd infra/terraform && terraform fmt -check && terraform init -backend=false && terraform validate`.

## Invariants

Each has a test or a CI step.

- **The truth stays in the world.** Designs and the service see `Observed`: accounts, devices, daily logs, payout
  accounts, verdicts and selfie templates. Who is behind an account and what each cash-out really is stay in `World`
  (`WorldTest.theObservedStoreHoldsNoTruth`).
- **The world does not read the guard's settings** (`WorldTest.theWorldReadsOnlyItsOwnConfig`).
- **Same luck for every design.** Every draw is keyed on (seed, what is drawn, which entity)
  (`WorldTest.sameSeedSameWorldSameOutcome`).
- **The model scores and code decides.** The model returns a probability; `Policy` decides. An appeal reading is data:
  it can move a case up the queue, never pay or unblock it (`CoreTest`, `ClaudeAppealReaderTest`).
- **A person decides every ban.** The guard pays, asks for a selfie or holds; only an analyst's verdict bans
  (`CoreTest.theGuardNeverBansOnItsOwnAndAConfirmedRingWaitsForAPerson`, gate `everyBanByAPerson`).
- **The model learns only what a person or a payment network confirmed.** Cash-outs an automatic rule blocked are
  not labels (`Evaluate.train`; the `rule_labels` design measures what happens otherwise).
- **Player text is data.** Appeals are wrapped as data for the model, read against a closed schema, and replies never
  name a check (`CoreTest.appealsAreReadInFourLanguagesAndClaimsCannotBeatHardEvidence`).
- **A cash-out is decided once.** One row per request id in MySQL, decision and outbox event in one transaction
  (`JdbcDecisionStoreTest.aRequestIsDecidedOnceEvenWhenEightPodsAskAtOnce`).
- **Settings live in `config/`**: the world in `world.json`, the designs in `guard.json`, thresholds in `gates.json`,
  seeds in `seeds.json`. Reports carry their hash.
- **Reports and the shipped model are generated, never edited.** CI regenerates REPORT.md, the README block and
  `models/risk-model.json` and fails on a diff.

## Held-out seeds

- `./gradlew :eval:heldout` writes `reports/heldout.lock` and refuses to run while it exists (`EvaluateTest.heldOutRunsOnce`).
  CI checks the lock was committed once.
- Gates and the guard's settings were set on the development seeds before the held-out run. Never change them after it.

## Data

Everything is simulated. No real player, device, payment or face data.
