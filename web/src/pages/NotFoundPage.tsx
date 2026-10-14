import { Link } from "react-router-dom";
import { useT } from "../i18n";

export function NotFoundPage() {
  const t = useT();
  return (
    <div className="state-card">
      <p className="state-card__title">{t("notFound.title")}</p>
      <Link to="/" className="btn btn--secondary">
        {t("common.backToHome")}
      </Link>
    </div>
  );
}
