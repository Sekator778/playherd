import { Suspense, lazy, type ComponentProps } from "react";
import { Skeleton } from "./Common";
import type { CrowdChart as CrowdChartType } from "./CrowdChart";

// recharts (the chart library) is the single biggest dependency in the bundle — split it into
// its own chunk so the rest of the app (including every screen that never shows a chart) loads
// without paying for it upfront.
const CrowdChartLazy = lazy(() => import("./CrowdChart").then((m) => ({ default: m.CrowdChart })));

export function LazyCrowdChart(props: ComponentProps<typeof CrowdChartType>) {
  return (
    <Suspense fallback={<Skeleton height={props.variant === "compact" ? 240 : 360} />}>
      <CrowdChartLazy {...props} />
    </Suspense>
  );
}
