import { Component, type ErrorInfo, type ReactNode } from "react";

interface ErrorBoundaryProps {
  children: ReactNode;
  /** What stands where `children` were when one of them threw while rendering (or, for a lazy one, while loading). */
  fallback: ReactNode;
  /** When this changes, a boundary that has caught something tries its children again (new data may draw what the old could not). */
  resetKey?: unknown;
}

interface ErrorBoundaryState {
  failed: boolean;
}

/**
 * Keeps an error thrown while rendering one part of a page to that part. React unmounts the WHOLE tree when nothing catches
 * an error from a render — a chart that could not draw its data used to take the header, the tab bar and every other page
 * with it. A boundary needs a class: there is no hook for it.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { failed: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { failed: true };
  }

  componentDidCatch(error: unknown, info: ErrorInfo): void {
    // The player sees the fallback; whoever debugs it needs the error and where it came from.
    console.error("A part of the page could not be drawn:", error, info.componentStack);
  }

  componentDidUpdate(previous: ErrorBoundaryProps): void {
    if (this.state.failed && previous.resetKey !== this.props.resetKey) this.setState({ failed: false });
  }

  render(): ReactNode {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}

/** The line that stands where a chart could not be drawn: short, and it does not pretend there was nothing to draw. */
export const CHART_FAILED_TEXT = "This chart could not be drawn.";

/** An {@link ErrorBoundary} for one chart: whatever it throws, the rest of the page stays. */
export function ChartBoundary({ children, resetKey }: { children: ReactNode; resetKey?: unknown }) {
  return (
    <ErrorBoundary resetKey={resetKey} fallback={<p className="chart-failed muted">{CHART_FAILED_TEXT}</p>}>
      {children}
    </ErrorBoundary>
  );
}
