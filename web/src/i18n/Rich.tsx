import type { ReactNode } from "react";
import { Link } from "react-router-dom";

/** Renders a dictionary string with light markup, so a sentence with an emphasised word or a link stays ONE translatable
 *  string (word order differs between languages) instead of being cut into JSX fragments: `**bold**` becomes `<strong>`,
 *  and — when `to` is given — `[[label]]` becomes a link to it. */
export function Rich({ text, to }: { text: string; to?: string }): ReactNode {
  return (
    <>
      {text.split(/(\*\*[^*]+\*\*|\[\[[^\]]+\]\])/).map((part, i) => {
        if (part.startsWith("**") && part.endsWith("**") && part.length > 4) return <strong key={i}>{part.slice(2, -2)}</strong>;
        if (to && part.startsWith("[[") && part.endsWith("]]")) return <Link key={i} to={to}>{part.slice(2, -2)}</Link>;
        return part;
      })}
    </>
  );
}
