import { type DesignRow, KINDS, SHORT, extra, usd } from "../../shared/data/report";
import table from "../../shared/ui/table.module.css";

const SHOWN = ["typical", "strict_rules", "guard", "no_graph", "no_step_up"];

/** Fraud loss by kind of fraud, in dollars a week, for the baselines, the guard and the two ablations that matter most. */
export function Kinds({ designs, weeks }: { designs: Record<string, DesignRow>; weeks: number }) {
  const cols = SHOWN.filter((k) => designs[k]);
  return (
    <div className={table.wrap}>
      <table className={`${table.table} num`}>
        <caption>Fraud paid out minus the ad revenue it brought, per week. Taken-over accounts count from the day they change hands.</caption>
        <thead>
          <tr>
            <th scope="col">Kind of fraud</th>
            {cols.map((k) => (
              <th key={k} scope="col">
                {SHORT[k]}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {KINDS.map((kind) => (
            <tr key={kind.key}>
              <th scope="row">{kind.label}</th>
              {cols.map((k) => (
                <td key={k} data-guard={k === "guard" || undefined}>
                  {usd(extra(designs[k]!, `fraud_loss_${kind.key}`) / weeks)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
