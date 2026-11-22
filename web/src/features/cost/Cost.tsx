import { COSTS, type DesignRow, SHORT, usd } from "../../shared/data/report";
import styles from "./cost.module.css";

/** Where each design's weekly cost goes, stacked, cheapest first, on one shared scale. */
export function Cost({ designs }: { designs: Record<string, DesignRow> }) {
  const rows = Object.entries(designs).sort((a, b) => a[1].total_cost[0] - b[1].total_cost[0]);
  const max = Math.max(...rows.map(([, d]) => d.total_cost[0]));
  return (
    <figure className={styles.figure} aria-label="Weekly cost of fraud and friction by design, split into its parts">
      <div className={styles.legend} aria-hidden="true">
        {COSTS.map((c, i) => (
          <span key={c.key} className={styles.key}>
            <i style={{ background: `var(--cost-${i + 1})` }} />
            {c.label}
          </span>
        ))}
      </div>
      {rows.map(([k, d]) => (
        <div key={k} className={styles.row} data-guard={k === "guard" || undefined}>
          <p className={styles.name}>{SHORT[k] ?? d.label}</p>
          <div className={styles.track}>
            <div className={styles.bar} style={{ width: `${(d.total_cost[0] / max) * 100}%` }} role="img"
              aria-label={`${SHORT[k] ?? d.label}: ${usd(d.total_cost[0])} a week, ${COSTS.map((c) => `${c.label.toLowerCase()} ${usd((d[c.key] as number[])[0]!)}`).join(", ")}`}>
              {COSTS.map((c, i) => {
                const v = (d[c.key] as number[])[0]!;
                return v > 0 ? <span key={c.key} className={styles.seg} style={{ flexGrow: v, background: `var(--cost-${i + 1})` }} title={`${c.label}: ${usd(v)} a week`} /> : null;
              })}
            </div>
          </div>
          <p className={`${styles.total} num`}>{usd(d.total_cost[0])}</p>
        </div>
      ))}
    </figure>
  );
}
