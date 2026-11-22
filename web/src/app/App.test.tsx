import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import fixture from "../test/report.fixture.json";
import { App } from "./App";

afterEach(() => vi.unstubAllGlobals());

describe("App", () => {
  it("shows a skeleton, then the cash-out flow, the cost split, fraud by kind and every design", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(fixture), { status: 200 })));
    render(<App />);
    expect(screen.getByRole("status")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText(/What happens to every 1,000 cash-outs/)).toBeInTheDocument());
    expect(screen.getByLabelText(/Weekly cost of fraud and friction by design/)).toBeInTheDocument();
    expect(screen.getByText("Emulator farms")).toBeInTheDocument();
    expect(screen.getAllByText("This guard").length).toBeGreaterThan(1);
  });

  it("offers a retry when the report fails", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("x", { status: 500 })));
    render(<App />);
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("HTTP 500"));
  });

  it("explains a missing report", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("", { status: 404 })));
    render(<App />);
    await waitFor(() => expect(screen.getByText(/No held-out report yet/)).toBeInTheDocument());
  });
});
