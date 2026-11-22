import { describe, expect, it } from "vitest";

import { type DesignRow, extra, pct, usd } from "./report";

describe("report helpers", () => {
  it("formats money and shares", () => {
    expect(usd(1412.4)).toBe("$1,412");
    expect(pct(0.698)).toBe("70%");
  });
  it("reads a missing extra as zero", () => {
    expect(extra({ label: "x" } as unknown as DesignRow, "flow_honest_paid_now")).toBe(0);
  });
});
