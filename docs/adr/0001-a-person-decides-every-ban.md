# 1. A person decides every ban

## Context
In rewarded apps the loudest complaints are bans at the moment of cash-out: weeks of play, then "invalid activity",
and no one to ask. Automatic bans on a red flag (a VPN, a shared phone) catch fraud but hit honest players whose
situation explains the flag.

## Decision
The guard has three outcomes: pay, ask for a selfie, or hold for an analyst. Only an analyst's verdict bans an
account, and the verdict records who made it. An account a person confirmed as fraud is refused on later requests.

## Consequences
Analyst hours are part of the cost the report counts, so the guard has to hold few honest cash-outs to win. The
`strict_rules` design measures the alternative.
