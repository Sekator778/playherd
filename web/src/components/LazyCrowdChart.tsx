import { Suspense, lazy, type ComponentProps } from "react";
import { Skeleton } from "./Common";
import type { CrowdChart as CrowdChartType } from "./CrowdChart";
import { ChartBoundary } from "./ErrorBoundary";

// recharts (the chart library) is the single biggest dependency in the bundle — split it into
// its own chunk so the rest of the app (including every screen that never shows a chart) loads
// without paying for it upfront.
const CrowdChartLazy = lazy(() => import("./CrowdChart").then((m) => ({ default: m.CrowdChart })));

/**
 * The crowd chart, loaded when first needed — and kept to itself when it cannot be drawn: an error thrown while it renders (its
 * data, the library) or while its chunk loads (offline, a deploy that replaced the file) stops at `ChartBoundary` and leaves a
 * line in the chart's place, instead of unmounting the whole app. New history gives it another try.
 */
export function LazyCrowdChart(props: ComponentProps<typeof CrowdChartType>) {
  return (
    <ChartBoundary resetKey={props.history}>
      <Suspense fallback={<Skeleton height={props.variant === "compact" ? 240 : 360} />}>
        <CrowdChartLazy {...props} />
      </Suspense>
    </ChartBoundary>
  );
}
