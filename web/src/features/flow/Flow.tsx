import { motion as m, useReducedMotion } from "framer-motion";

import { type DesignRow, OUTCOMES, SHORT, extra, num } from "../../shared/data/report";
import { motion } from "../../shared/theme/motion";
import styles from "./flow.module.css";

const GROUPS = [
  { who: "honest", title: "Honest players' cash-outs", good: "paid_now" },
  { who: "fraud", title: "Fraudsters' cash-outs", good: "not_paid" },
] as const;

/** What happens to every 1,000 cash-outs, honest and fraudulent, under today's typical setup and under the guard. */
export function Flow({ designs, compare = ["typical", "guard"] }: { designs: Record<string, DesignRow>; compare?: string[] }) {
  const reduce = useReducedMotion();
  const rows = compare.filter((k) => designs[k]);
  return (
    <figure className={styles.figure} aria-label="What happens to every 1,000 cash-outs, honest and fraudulent">
      <div className={styles.legend} aria-hidden="true">
        {OUTCOMES.map((o, i) => (
          <span key={o.key} className={styles.key}>
            <i style={{ background: `var(--flow-${i + 1})` }} />
            {o.label}
          </span>
        ))}
      </div>
      {GROUPS.map((g, gi) => (
        <div key={g.who} className={styles.group}>
          <p className={styles.groupTitle}>{g.title}</p>
          {rows.map((k, ri) => {
            const d = designs[k]!;
            const counts = OUTCOMES.map((o) => extra(d, `flow_${g.who}_${o.key}`));
            const total = counts.reduce((a, b) => a + b, 0) || 1;
            const per1k = counts.map((c) => (c / total) * 1000);
            const order = gi * rows.length + ri;
            return (
              <div key={k} className={styles.row} data-guard={k === "guard" || undefined}>
                <p className={styles.name}>{SHORT[k] ?? d.label}</p>
                <div className={styles.bar} role="img" aria-label={`${SHORT[k] ?? d.label}: ${OUTCOMES.map((o, i) => `${num(per1k[i]!)} ${o.label.toLowerCase()}`).join(", ")} per 1,000`}>
                  {OUTCOMES.map((o, i) => {
                    const share = per1k[i]! / 1000;
                    if (share <= 0) return null;
                    return (
                      <m.span
                        key={o.key}
                        className={styles.seg}
                        style={{ flexGrow: share, background: `var(--flow-${i + 1})` }}
                        data-light={i >= 2 || undefined}
                        title={`${o.label}: ${num(per1k[i]!)} of 1,000`}
                        initial={reduce ? false : { opacity: 0, scaleX: 0 }}
                        animate={{ opacity: 1, scaleX: 1 }}
                        transition={{ duration: motion.base, delay: order * motion.stagger + i * 0.05, ease: motion.ease }}
                      >
                        {share >= 0.07 && <span className="num">{num(per1k[i]!)}</span>}
                      </m.span>
                    );
                  })}
                </div>
                <p className={`${styles.focus} num`}>
                  {num(per1k[OUTCOMES.findIndex((o) => o.key === g.good)]!)} <span>{g.who === "honest" ? "paid at once" : "never paid"}</span>
                </p>
              </div>
            );
          })}
        </div>
      ))}
    </figure>
  );
}
