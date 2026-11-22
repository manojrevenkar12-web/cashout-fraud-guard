import { useCallback, useEffect, useState } from "react";

export type SetName = "heldout" | "dev";
export type Interval = [number, number, number];

export interface DesignRow {
  label: string;
  total_cost: Interval;
  fraud_loss: Interval;
  friction_loss: Interval;
  analyst_cost: Interval;
  support_cost: Interval;
  step_up_cost: Interval;
  fraud_paid_share: Interval;
  wrong_bans_per_10k: Interval;
  honest_late_share: Interval;
  analyst_hours: Interval;
  total_cost_cut_vs_typical: Interval;
  fraud_loss_cut_vs_typical: Interval;
  bans_by_a_person_share: number;
  [extra: string]: unknown;
}

export interface Report {
  set: SetName;
  seeds: number[];
  config_sha: string;
  generated_at: string;
  facts: { accounts: number; fraud_accounts: number; live_cashouts: number; live_fraud_cashouts: number; training_examples: number; training_positives: number; weeks: number };
  designs: Record<string, DesignRow>;
  model_weights_first_seed: Record<string, number>;
}

export type LoadState = { status: "loading" } | { status: "error"; message: string } | { status: "missing" } | { status: "ready"; report: Report };

/** Loads reports/<set>.json as written by `./gradlew :eval:evaluate` or `:eval:heldout`. */
export function useReport(set: SetName): { state: LoadState; retry: () => void } {
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let live = true;
    setState({ status: "loading" });
    fetch(`./reports/${set}.json`)
      .then(async (res) => {
        if (!live) return;
        if (res.status === 404) return setState({ status: "missing" });
        if (!res.ok) throw new Error(`The report did not load (HTTP ${res.status}).`);
        const report = (await res.json()) as Report;
        if (live) setState({ status: "ready", report });
      })
      .catch((err: unknown) => live && setState({ status: "error", message: err instanceof Error ? err.message : String(err) }));
    return () => {
      live = false;
    };
  }, [set, attempt]);
  const retry = useCallback(() => setAttempt((n) => n + 1), []);
  return { state, retry };
}

/** Short names for charts; tables show each design's full label. */
export const SHORT: Record<string, string> = {
  typical: "Typical setup",
  strict_rules: "Strict rules",
  model_only: "Risk model alone",
  guard: "This guard",
  no_graph: "No ring graph",
  no_step_up: "No selfie",
  no_ring_review: "Ring reviewed one by one",
  rule_labels: "Trained on rule bans",
  no_reply_drafts: "No reply drafts",
};

export const COSTS = [
  { key: "fraud_loss", label: "Fraud loss" },
  { key: "friction_loss", label: "Honest players lost" },
  { key: "analyst_cost", label: "Analysts" },
  { key: "support_cost", label: "Support" },
  { key: "step_up_cost", label: "Selfies" },
] as const;

export const OUTCOMES = [
  { key: "paid_now", label: "Paid at once" },
  { key: "paid_after_selfie", label: "Paid after a selfie" },
  { key: "paid_later", label: "Paid after review" },
  { key: "not_paid", label: "Not paid" },
] as const;

export const KINDS = [
  { key: "farm", label: "Emulator farms" },
  { key: "multi", label: "One person, many accounts" },
  { key: "offer", label: "Offerwall fraud" },
  { key: "takeover", label: "Taken-over accounts" },
  { key: "geo_vpn", label: "Geo arbitrage on VPN" },
] as const;

export const extra = (d: DesignRow, key: string): number => (typeof d[key] === "number" ? (d[key] as number) : 0);
export const pct = (x: number, d = 0) => `${(x * 100).toFixed(d)}%`;
export const num = (x: number, d = 0) => new Intl.NumberFormat("en-GB", { maximumFractionDigits: d, minimumFractionDigits: d }).format(x);
export const usd = (x: number) => `$${num(x)}`;
