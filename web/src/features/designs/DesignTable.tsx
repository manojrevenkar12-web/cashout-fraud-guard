import { type DesignRow, type Interval, num, pct, usd } from "../../shared/data/report";
import styles from "../../shared/ui/table.module.css";

const ci = (v: Interval, f: (x: number) => string) => (
  <>
    {f(v[0])}
    <span className={styles.ci}>
      {f(v[1])} to {f(v[2])}
    </span>
  </>
);

/** Every design on the same seeds; 95% intervals resample whole weeks. */
export function DesignTable({ designs }: { designs: Record<string, DesignRow> }) {
  return (
    <div className={styles.wrap}>
      <table className={`${styles.table} num`}>
        <caption>Weekly means. Wrong bans are honest accounts banned per 10,000 honest cash-outs.</caption>
        <thead>
          <tr>
            <th scope="col">Design</th>
            <th scope="col">Weekly cost</th>
            <th scope="col">Cut vs typical</th>
            <th scope="col">Fraud loss</th>
            <th scope="col">Wrong bans</th>
            <th scope="col">Honest paid after 24 h</th>
            <th scope="col">Analyst hours</th>
            <th scope="col">Bans by a person</th>
          </tr>
        </thead>
        <tbody>
          {Object.entries(designs).map(([key, d]) => (
            <tr key={key} data-tone={key === "guard" ? "guard" : undefined}>
              <th scope="row">{d.label}</th>
              <td>{ci(d.total_cost, usd)}</td>
              <td>{key === "typical" ? "–" : ci(d.total_cost_cut_vs_typical, (x) => pct(x))}</td>
              <td>{usd(d.fraud_loss[0])}</td>
              <td>{num(d.wrong_bans_per_10k[0], 1)}</td>
              <td>{pct(d.honest_late_share[0], 1)}</td>
              <td>{num(d.analyst_hours[0], 1)}</td>
              <td>{pct(d.bans_by_a_person_share)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
