/**
 * FIFO result-moment queue with the "collapse after 2" rule (`docs/product/design/
 * result-moment.md` §1): the first two pending moments are always shown individually, full
 * duration; a third (and anything beyond it) collapses into one summary card — "не скрывает ни
 * одну сумму — только группирует их показ", so every event is still carried in the overflow
 * card's `events` list, not dropped. Pure logic, no React (`implementation-plan.md` §2), so
 * `ResultMomentHost.tsx` is the only thing that touches React state.
 */
import type { ResultEvent } from "./resultEvent";

export type DisplayItem = { kind: "single"; event: ResultEvent } | { kind: "overflow"; events: ResultEvent[] };

const INDIVIDUAL_BUDGET = 2;

export class ResultQueue {
  private pending: ResultEvent[] = [];
  private shownIndividually = 0;

  push(event: ResultEvent): void {
    this.pending.push(event);
  }

  size(): number {
    return this.pending.length;
  }

  /** What should be on screen right now, or `null` if there's nothing queued — the host renders
   *  at most one of these at a time (§1's "не блокирует... один момент на экране" invariant). */
  current(): DisplayItem | null {
    if (this.pending.length === 0) {
      this.shownIndividually = 0;
      return null;
    }
    if (this.shownIndividually < INDIVIDUAL_BUDGET) {
      return { kind: "single", event: this.pending[0] };
    }
    return { kind: "overflow", events: [...this.pending] };
  }

  /** Dismiss whatever `current()` returned — a natural hold-timeout or a tap-to-skip both call
   *  this (§1: "тап... мгновенный переход к dismiss"). */
  advance(): void {
    const displayed = this.current();
    if (!displayed) return;
    if (displayed.kind === "single") {
      this.pending.shift();
      this.shownIndividually += 1;
    } else {
      this.pending = [];
      // `shownIndividually` resets on the next `current()` call once the queue is empty.
    }
  }
}
