import { AnimatePresence, motion as m } from "framer-motion";
import { useState } from "react";

import { Cost } from "../features/cost/Cost";
import { DesignTable } from "../features/designs/DesignTable";
import { Flow } from "../features/flow/Flow";
import { Kinds } from "../features/kinds/Kinds";
import { type Report, type SetName, num, pct, usd, useReport } from "../shared/data/report";
import { motion } from "../shared/theme/motion";
import { ErrorState, MissingState, Skeleton } from "../shared/ui/states";
import styles from "./App.module.css";

const SETS: { key: SetName; label: string }[] = [
  { key: "heldout", label: "Held-out seeds" },
  { key: "dev", label: "Development seeds" },
];

const initialSet = (): SetName => (new URLSearchParams(window.location.search).get("set") === "dev" ? "dev" : "heldout");

export function App() {
  const [set, setSet] = useState<SetName>(initialSet);
  const { state, retry } = useReport(set);
  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.title}>Pay the players, stop the farms</h1>
          <p className={styles.lede}>
            Cash-out decisions for a simulated rewarded-gaming app. Every design meets the same players and the same fraudsters; the world knows who is
            who and counts what each design pays out, who it bans and what that costs.
          </p>
        </div>
        <div className={styles.switch} role="group" aria-label="Seed set">
          {SETS.map((s) => (
            <button key={s.key} type="button" aria-pressed={set === s.key} onClick={() => setSet(s.key)}>
              {s.label}
            </button>
          ))}
        </div>
      </header>
      <AnimatePresence mode="wait">
        <m.main key={`${set}-${state.status}`} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: motion.quick }}>
          {state.status === "loading" && <Skeleton />}
          {state.status === "error" && <ErrorState message={state.message} onRetry={retry} />}
          {state.status === "missing" && <MissingState set={set} onSwitch={set === "heldout" ? () => setSet("dev") : undefined} />}
          {state.status === "ready" && <Results report={state.report} />}
        </m.main>
      </AnimatePresence>
    </div>
  );
}

function Results({ report }: { report: Report }) {
  const d = report.designs;
  const g = d.guard;
  const t = d.typical;
  if (!g || !t) return <MissingState set={report.set} />;
  const f = report.facts;
  return (
    <>
      <section className={styles.hero}>
        <p className={styles.headline}>
          The guard cut the weekly cost of fraud and friction by <strong>{pct(g.total_cost_cut_vs_typical[0])}</strong> against a typical setup. It banned{" "}
          {num(g.wrong_bans_per_10k[0], 1)} honest players per 10,000 honest cash-outs instead of {num(t.wrong_bans_per_10k[0], 0)}, and a person decided every ban.
        </p>
        <Flow designs={d} />
      </section>
      <section className={styles.section}>
        <h2 className={styles.h2}>Where the weekly cost goes</h2>
        <p className={styles.note}>
          Banning on red flags stops some fraud and loses more in honest players than the fraud cost. The guard spends analyst time instead, and a case file with
          the whole ring keeps that time short.
        </p>
        <Cost designs={d} />
      </section>
      <section className={styles.section}>
        <h2 className={styles.h2}>Which fraud gets through</h2>
        <p className={styles.note}>
          Without the ring graph, one person with five accounts looks like five honest players. Without the selfie, the graph cannot tell that person from a family
          sharing a phone.
        </p>
        <Kinds designs={d} weeks={f.weeks} />
      </section>
      <section className={styles.section}>
        <h2 className={styles.h2}>Every design, the same players</h2>
        <p className={styles.note}>The ablations remove or change one part of the guard at a time. Intervals are 95%, resampling whole weeks.</p>
        <DesignTable designs={d} />
      </section>
      <footer className={`${styles.footer} num`}>
        Seeds {report.seeds.join(", ")} · {num(f.accounts)} accounts, {num(f.fraud_accounts)} fraudulent · {num(f.live_cashouts)} cash-outs over 8 live weeks ·
        model trained on {num(f.training_examples)} confirmed labels · typical fraud loss {usd(t.fraud_loss[0])} a week · config {report.config_sha} · generated{" "}
        {report.generated_at}
      </footer>
    </>
  );
}
