/**
 * Which `Api` implementation the app talks to — task item 1: "Select via VITE_API_MODE=mock|real
 * (default mock for `npm run dev`, real for the Docker build)". Read once at module load (Vite
 * inlines `import.meta.env.*` at build time, so this is a compile-time constant in the built
 * bundle, not something that can change at runtime).
 */
export type ApiMode = "mock" | "real";

export const API_MODE: ApiMode = import.meta.env.VITE_API_MODE === "real" ? "real" : "mock";
