import { useSession } from "../api/ApiContext";
import { useMyPositions } from "../api/useMyPositions";
import { useProMode } from "./proMode";

/** Whether the pinned "My bet" card is on screen: a signed-in player with an open bet, outside the Pro view. The layout keeps room for it. */
export function useMyBetDockVisible(): boolean {
  const { player } = useSession();
  const { positions } = useMyPositions();
  const pro = useProMode();
  return !pro && player !== null && positions.length > 0;
}
