import { useT } from "../i18n";
import { setProMode, useProMode } from "./proMode";
import "./ProView.css";

/** The Pro view switch (Р-98): off by default, remembered per browser. A pressed-state button with a word — never colour alone. */
export function ProToggle({ className = "" }: { className?: string }) {
  const t = useT();
  const pro = useProMode();
  return (
    <button type="button" className={`pro-toggle ${className}`} aria-pressed={pro} title={t("pro.hint")} onClick={() => setProMode(!pro)}>
      {t("pro.toggle")}
    </button>
  );
}
