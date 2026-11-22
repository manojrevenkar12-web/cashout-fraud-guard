# 5. A simulated app to compare designs on the same luck

## Context
None of this can be measured on a real app from the outside, and comparing designs on different random draws mixes
design effects with luck.

## Decision
`world/` simulates players, five kinds of fraud, selfies, analysts, appeals, chargebacks and churn, reads only
`config/world.json`, and keys every draw on the seed and the entity. Development seeds set the gates; held-out seeds
run once behind a lock. The service ships the model the evaluation exported and the policy settings it measured.

## Consequences
The numbers say how designs compare in this world. Every assumption is a line in one file that anyone can change and
rerun.
