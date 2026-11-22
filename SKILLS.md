# Repeatable tasks

## Add a signal
1. Add it to `Signals.NAMES`, `Signals.REASONS` and `Signals.features`; if it comes from the link graph, add its index
   to `Signals.GRAPH` so the `no_graph` design leaves it out.
2. If the world does not produce what it reads, add it to `DayLog` and to `World.logFor`.
3. Dev run, gate, readme, then `./gradlew :eval:exportModel` so the service ships the retrained model.

## Add a kind of fraud
1. Add it to `Kind`, generate its accounts in `World.build`, and give it a selfie completion rate under `stepUp` in
   `config/world.json` and a revenue per cent paid.
2. Add how it gets reported later (chargeback, clawback) in `World.pay` if it does.
3. Dev run. The report's loss-by-kind numbers show whether any design catches it.

## Tune the guard
Change `payBelow`, `reviewFrom` or `selfieValidDays` in `config/guard.json` on development seeds only, and look at the
whole trade: fraud paid out, players lost, analyst hours and selfies. Never after the held-out run.

## Switch the appeal reader to Claude
Set `ANTHROPIC_API_KEY` (and optionally `ANTHROPIC_MODEL`). The reader sends the message as data with a forced
`record_appeal` tool call; anything outside the schema falls back to the rules. Before switching in production, run
both readers over a sample of real appeals and compare language and claim with the rules reader.

## Deploy to GCP
1. `cd infra/terraform && terraform apply -var project_id=... -var network=...` creates Cloud SQL, Pub/Sub, secrets,
   the image repository and the service account with Workload Identity.
2. `./gradlew :service:installDist && docker build -t REGION-docker.pkg.dev/PROJECT/cashout-fraud-guard/guard:TAG .` and push.
3. Fill the placeholders in `deploy/k8s/guard.yaml`, create the `cashout-fraud-guard` secret from Secret Manager, apply.
   Liquibase migrates the schema on start.

## Run the held-out seeds (once)
`./gradlew :eval:heldout :eval:gate :eval:readme`, then commit the lock, the report and the README in one commit.

## Refresh the dashboard screenshots
`cd web && npm run build`, then `python3 scripts/screenshots.py`.
