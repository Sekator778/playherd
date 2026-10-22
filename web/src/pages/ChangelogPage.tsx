import { Link } from "react-router-dom";
import { useLocale, useT, type Locale } from "../i18n";
import { HTML_LANG } from "../i18n/locale";
import { CHANGELOG } from "./changelog/entries";

/** The day in the reader's language; the entry's `date` is a UTC day, so the formatter stays in UTC. */
function formatDay(iso: string, locale: Locale): string {
  return new Intl.DateTimeFormat(HTML_LANG[locale], { day: "numeric", month: "long", year: "numeric", timeZone: "UTC" }).format(new Date(`${iso}T00:00:00Z`));
}

/** Public, no sign-in: linked from the footer and from every update post. Static content (`changelog/entries.ts`). */
export function ChangelogPage() {
  const t = useT();
  const { locale } = useLocale();
  return (
    <div className="stack" style={{ gap: 28 }}>
      <h1>{t("changelog.title")}</h1>
      <p>{t("changelog.intro")}</p>

      {CHANGELOG.map((entry) => (
        <section key={entry.date}>
          <h2>
            <time dateTime={entry.date}>{formatDay(entry.date, locale)}</time>
          </h2>
          <ul>
            {entry.items.map((key) => (
              <li key={key}>{t(key)}</li>
            ))}
          </ul>
        </section>
      ))}

      <p>
        <Link to="/">{t("changelog.back")}</Link>
      </p>
    </div>
  );
}
