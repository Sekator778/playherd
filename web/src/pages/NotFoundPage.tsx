import { Link } from "react-router-dom";

export function NotFoundPage() {
  return (
    <div className="state-card">
      <p className="state-card__title">Page not found.</p>
      <Link to="/" className="btn btn--secondary">
        Back to Home
      </Link>
    </div>
  );
}
