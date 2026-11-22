# 3. The model learns only what a person or a payment network confirmed

## Context
The history the model trains on was produced by the old setup, which blocked every cash-out made over a VPN. Treating
those blocks as fraud labels teaches the model that VPN use is fraud, and the new guard would repeat the old rule's
mistakes with a confident score.

## Decision
Labels come from analyst verdicts, chargebacks and ad-network clawbacks. Cash-outs an automatic rule blocked are left
out of training. A network signal counts the share of a VPN or datacenter network's accounts already confirmed as
fraud, not how many accounts use it, so a public VPN exit is not evidence.

## Consequences
Fewer positive labels. The `rule_labels` design trains on the rule's bans and shows the cost.
