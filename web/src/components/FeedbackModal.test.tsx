/**
 * The feedback form (Р-81): what it says, what it sends, what it does when sending fails, and that it never takes the
 * person's words from them. Driven the way a person drives it — through the buttons, the box and the keyboard.
 */
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiInstanceContext } from "../api/ApiContext";
import { fakeApi } from "../api/apiTestUtils";
import { ApiRequestError, FEEDBACK_MAX_LENGTH, type Api, type FeedbackReceiptDto, type FeedbackRequest } from "../api/contract";
import {
  FEEDBACK_FAILED_TEXT,
  FEEDBACK_RATE_LIMITED_TEXT,
  FEEDBACK_SEND_TIMEOUT_MS,
  FEEDBACK_SENT_KEY,
  isFeedbackSent,
  openFeedbackForm,
  resetFeedbackForTests,
} from "../api/feedback";
import { handleBackPress } from "../fx/backHandlers";
import { FeedbackModal } from "./FeedbackModal";
import { FeedbackStrip } from "./FeedbackStrip";

type Submit = (req: FeedbackRequest) => Promise<FeedbackReceiptDto>;

/** A promise the test settles by hand — a send that is "on its way". */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function renderForm(submit: Submit = () => Promise.resolve({ id: 1 }), path = "/play/BTC?side=up", { strip = false } = {}) {
  const api: Api = fakeApi({ submitFeedback: submit });
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ApiInstanceContext.Provider value={api}>
        <button type="button" onClick={openFeedbackForm}>
          open the form
        </button>
        {strip && <FeedbackStrip />}
        <FeedbackModal />
      </ApiInstanceContext.Provider>
    </MemoryRouter>,
  );
}

async function openForm(user = userEvent.setup()) {
  await user.click(screen.getByRole("button", { name: "open the form" }));
  return screen.getByRole("dialog", { name: "Feedback" });
}

const box = () => screen.getByRole("textbox", { name: "What did you like? What didn't work for you?" });
const sendButton = () => screen.getByRole("button", { name: /^(Send|Sending…)$/ });

beforeEach(() => {
  window.localStorage.clear();
  resetFeedbackForTests();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  document.body.style.overflow = "";
});

describe("the form's words", () => {
  it("is not on the page until it is opened — it never opens by itself", () => {
    renderForm();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("opens as a dialog: a title, the prompt, an empty box limited to 2000 characters, Send and Cancel", async () => {
    renderForm();
    const dialog = await openForm();
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(within(dialog).getByRole("heading", { name: "Feedback" })).toBeInTheDocument();
    expect(within(dialog).getByText("What did you like? What didn't work for you?")).toBeInTheDocument();
    expect(box()).toHaveValue("");
    expect(box()).toHaveAttribute("maxlength", String(FEEDBACK_MAX_LENGTH));
    expect(FEEDBACK_MAX_LENGTH).toBe(2000);
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeInTheDocument();
    expect(sendButton()).toBeInTheDocument();
  });

  it("asks for nothing else — no email field, no name field", async () => {
    renderForm();
    const dialog = await openForm();
    expect(within(dialog).getAllByRole("textbox")).toHaveLength(1);
    expect(dialog.querySelectorAll("input")).toHaveLength(0);
    expect(within(dialog).queryByLabelText(/e-?mail/i)).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/name/i)).not.toBeInTheDocument();
  });

  it("says in plain words that no email or name is needed, and what is kept with the message", async () => {
    renderForm();
    const dialog = await openForm();
    const note = within(dialog).getByText(/No email or name needed/);
    expect(note).toHaveTextContent(/country/);
    expect(note).toHaveTextContent(/IP address/);
    expect(note).toHaveTextContent(/the address itself isn't saved with it/);
    expect(note).toHaveTextContent(/time zone/);
    expect(note).toHaveTextContent(/language/);
    expect(note).toHaveTextContent(/the page you're on/);
    expect(note).toHaveTextContent(/if you're signed in, your player ID/);
  });

  it("carries the database's credit: 'IP Geolocation by DB-IP', a link to db-ip.com that opens in a new tab", async () => {
    renderForm();
    const dialog = await openForm();
    const credit = within(dialog).getByRole("link", { name: "IP Geolocation by DB-IP" });
    expect(credit).toHaveAttribute("href", "https://db-ip.com");
    expect(credit).toHaveAttribute("target", "_blank");
    expect(credit.getAttribute("rel")).toMatch(/noopener/);
    expect(credit.getAttribute("rel")).toMatch(/noreferrer/);
  });

  it("the note and the credit are tied to the box, so a screen reader reads them with it", async () => {
    renderForm();
    await openForm();
    const described = (box().getAttribute("aria-describedby") ?? "").split(" ");
    expect(described).toContain("feedback-note");
    expect(document.getElementById("feedback-note")).toHaveTextContent(/No email or name needed/);
  });
});

describe("Send is for a message with something in it", () => {
  it("is disabled while the box is empty, and when it holds only whitespace", async () => {
    const user = userEvent.setup();
    renderForm();
    await openForm(user);
    expect(sendButton()).toBeDisabled();
    await user.type(box(), "   ");
    expect(sendButton()).toBeDisabled();
    await user.type(box(), "{Enter}{Enter}");
    expect(sendButton()).toBeDisabled();
  });

  it("is enabled as soon as there is a word, and disabled again when the word is deleted", async () => {
    const user = userEvent.setup();
    renderForm();
    await openForm(user);
    await user.type(box(), "a");
    expect(sendButton()).toBeEnabled();
    await user.clear(box());
    expect(sendButton()).toBeDisabled();
  });

  it("pressing it on an empty box sends nothing", async () => {
    const submit = vi.fn<Submit>(() => Promise.resolve({ id: 1 }));
    renderForm(submit);
    await openForm();
    fireEvent.submit(box().closest("form")!);
    expect(submit).not.toHaveBeenCalled();
  });
});

describe("the counter — near the limit only", () => {
  it("shows nothing for a short message", async () => {
    const user = userEvent.setup();
    renderForm();
    await openForm(user);
    await user.type(box(), "short");
    expect(screen.queryByText(/\/ 2,000/)).not.toBeInTheDocument();
  });

  it("appears from 1,800 characters: 'n / 2,000'", async () => {
    renderForm();
    await openForm();
    fireEvent.change(box(), { target: { value: "x".repeat(1799) } });
    expect(screen.queryByText(/\/ 2,000/)).not.toBeInTheDocument();
    fireEvent.change(box(), { target: { value: "x".repeat(1800) } });
    expect(screen.getByText("1,800 / 2,000")).toBeInTheDocument();
    fireEvent.change(box(), { target: { value: "x".repeat(1950) } });
    expect(screen.getByText("1,950 / 2,000")).toBeInTheDocument();
  });

  it("at the limit says so, in words", async () => {
    renderForm();
    await openForm();
    fireEvent.change(box(), { target: { value: "x".repeat(FEEDBACK_MAX_LENGTH) } });
    expect(screen.getByText(/2,000 \/ 2,000 — that's the limit/)).toBeInTheDocument();
    expect(sendButton()).toBeEnabled(); // exactly the limit is still a message
  });

  it("is tied to the box when it shows", async () => {
    renderForm();
    await openForm();
    fireEvent.change(box(), { target: { value: "x".repeat(1900) } });
    expect((box().getAttribute("aria-describedby") ?? "").split(" ")).toContain("feedback-counter");
  });
});

describe("sending", () => {
  it("sends the message trimmed, with where the form was opened (no query string) and what the browser says", async () => {
    const user = userEvent.setup();
    const submit = vi.fn<Submit>(() => Promise.resolve({ id: 7 }));
    renderForm(submit, "/play/BTC?side=up&series=daily");
    await openForm(user);
    await user.type(box(), "  Loved the chart.{Enter}The slip was slow.  ");
    await user.click(sendButton());

    await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
    const sent = submit.mock.calls[0][0];
    expect(sent.message).toBe("Loved the chart.\nThe slip was slow.");
    expect(sent.page).toBe("/play/BTC");
    expect(sent.client).toBe("web"); // a plain tab: no shell, not standalone
    expect(sent.language).toBe(navigator.language);
    expect(typeof sent.timeZone === "string" || sent.timeZone === null).toBe(true);
    expect(Object.keys(sent).sort()).toEqual(["client", "language", "message", "page", "timeZone"]);
  });

  it("says where it was OPENED, not where the person has gone since", async () => {
    // The sheet is modal, so the screen cannot change under it — but the rule is the one the contract states, and holds.
    const user = userEvent.setup();
    const submit = vi.fn<Submit>(() => Promise.resolve({ id: 1 }));
    renderForm(submit, "/status");
    await openForm(user);
    await user.type(box(), "hello");
    await user.click(sendButton());
    await waitFor(() => expect(submit).toHaveBeenCalled());
    expect(submit.mock.calls[0][0].page).toBe("/status");
  });

  it("while it is on its way: Send says 'Sending…' and is disabled, the box cannot be edited, Cancel stays", async () => {
    const user = userEvent.setup();
    const pending = deferred<FeedbackReceiptDto>();
    renderForm(() => pending.promise);
    const dialog = await openForm(user);
    await user.type(box(), "hello");
    await user.click(sendButton());

    expect(sendButton()).toHaveTextContent("Sending…");
    expect(sendButton()).toBeDisabled();
    expect(box()).toHaveAttribute("readonly");
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeEnabled();
    expect(dialog.querySelector("form")).toHaveAttribute("aria-busy", "true");

    await act(async () => pending.resolve({ id: 1 }));
  });

  it("one press, one message: pressing Send again while it is on its way sends nothing more", async () => {
    const user = userEvent.setup();
    const pending = deferred<FeedbackReceiptDto>();
    const submit = vi.fn<Submit>(() => pending.promise);
    renderForm(submit);
    await openForm(user);
    await user.type(box(), "hello");
    await user.click(sendButton());
    fireEvent.submit(box().closest("form")!); // a second submit by any route (Enter on a button, a double tap)
    fireEvent.submit(box().closest("form")!);
    expect(submit).toHaveBeenCalledTimes(1);
    await act(async () => pending.resolve({ id: 1 }));
    expect(submit).toHaveBeenCalledTimes(1);
  });

  it("when it is stored: thanks ('Thanks — we read every message.'), one Close button, and the strip is gone for good", async () => {
    const user = userEvent.setup();
    renderForm(() => Promise.resolve({ id: 1 }), "/", { strip: true });
    expect(screen.getByRole("region", { name: "Feedback" })).toBeInTheDocument();
    await openForm(user);
    await user.type(box(), "great");
    await user.click(sendButton());

    const dialog = await screen.findByRole("dialog", { name: "Thanks — we read every message." });
    expect(within(dialog).getByRole("heading", { name: "Thanks — we read every message." })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Close" })).toBeInTheDocument();
    expect(within(dialog).queryByRole("textbox")).not.toBeInTheDocument();
    expect(isFeedbackSent()).toBe(true);
    expect(window.localStorage.getItem(FEEDBACK_SENT_KEY)).toBe("1");
    expect(document.querySelector(".feedback-strip")).not.toBeInTheDocument();
  });

  it("the thank-you takes the focus (the form that had it is gone) and is announced", async () => {
    const user = userEvent.setup();
    renderForm();
    await openForm(user);
    await user.type(box(), "great");
    await user.click(sendButton());
    const close = await screen.findByRole("button", { name: "Close" });
    expect(close).toHaveFocus();
    expect(screen.getAllByRole("status").some((el) => el.textContent === "Thanks — we read every message.")).toBe(true);
  });

  it("Close leaves; opening it again is a fresh, empty form — what was sent is not offered again", async () => {
    const user = userEvent.setup();
    renderForm();
    await openForm(user);
    await user.type(box(), "great");
    await user.click(sendButton());
    await user.click(await screen.findByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await openForm(user);
    expect(box()).toHaveValue("");
    expect(sendButton()).toBeDisabled();
  });
});

describe("when it fails — the words stay, and the reason is told", () => {
  it("a rate limit (429): 'too many messages, try later' — and the text is still in the box", async () => {
    const user = userEvent.setup();
    renderForm(() => Promise.reject(new ApiRequestError("RATE_LIMITED", "429")));
    const dialog = await openForm(user);
    await user.type(box(), "my long thought");
    await user.click(sendButton());

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(FEEDBACK_RATE_LIMITED_TEXT);
    expect(FEEDBACK_RATE_LIMITED_TEXT).toMatch(/Too many messages/);
    expect(box()).toHaveValue("my long thought");
    expect(sendButton()).toBeEnabled();
    expect(isFeedbackSent()).toBe(false);
  });

  it.each([
    ["a server refusal", () => new ApiRequestError("OTHER", "boom")],
    ["a server that is restarting", () => new ApiRequestError("ENGINE_NOT_READY", "recovering")],
    ["a dead network", () => new Error("Network error — check your connection and try again.")],
  ])("%s: 'couldn't send, try again' — and the text is still in the box", async (_name, makeError) => {
    const user = userEvent.setup();
    renderForm(() => Promise.reject(makeError()));
    const dialog = await openForm(user);
    await user.type(box(), "my long thought");
    await user.click(sendButton());

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(FEEDBACK_FAILED_TEXT);
    expect(FEEDBACK_FAILED_TEXT).toMatch(/Couldn't send/);
    expect(box()).toHaveValue("my long thought");
    expect(sendButton()).toBeEnabled();
    expect(isFeedbackSent()).toBe(false);
  });

  it("the strip stays — a message that was not stored is not a message sent", async () => {
    const user = userEvent.setup();
    renderForm(() => Promise.reject(new Error("down")), "/", { strip: true });
    await openForm(user);
    await user.type(box(), "x");
    await user.click(sendButton());
    await screen.findByRole("alert");
    // the form is still open over it, so the strip is stepped aside — but it is there
    expect(document.querySelector(".feedback-strip")).toBeInTheDocument();
  });

  it("the person can try again, and the retry goes through", async () => {
    const user = userEvent.setup();
    const submit = vi
      .fn<Submit>()
      .mockRejectedValueOnce(new Error("down"))
      .mockResolvedValueOnce({ id: 2 });
    renderForm(submit);
    await openForm(user);
    await user.type(box(), "second try");
    await user.click(sendButton());
    await screen.findByRole("alert");
    await user.click(sendButton());

    expect(await screen.findByRole("heading", { name: "Thanks — we read every message." })).toBeInTheDocument();
    expect(submit).toHaveBeenCalledTimes(2);
    expect(submit.mock.calls[1][0].message).toBe("second try");
    expect(isFeedbackSent()).toBe(true);
  });

  it("typing again clears the old reason; the focus returns to Send so a keyboard is not left nowhere", async () => {
    const user = userEvent.setup();
    renderForm(() => Promise.reject(new Error("down")));
    await openForm(user);
    await user.type(box(), "x");
    await user.click(sendButton());
    await screen.findByRole("alert");
    expect(sendButton()).toHaveFocus();
    await user.type(box(), "y");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("a failure never prints the server's own sentence", async () => {
    const user = userEvent.setup();
    renderForm(() => Promise.reject(new ApiRequestError("OTHER", "java.lang.IllegalStateException: pool exhausted")));
    await openForm(user);
    await user.type(box(), "x");
    await user.click(sendButton());
    expect(await screen.findByRole("alert")).not.toHaveTextContent(/IllegalState|pool/);
  });
});

describe("a send that never answers (a phone on a poor connection) does not leave the form stuck", () => {
  beforeEach(() => {
    // Time moves on by itself too (`shouldAdvanceTime`), so Testing Library's `findBy…` polling still ticks; the tests also push it by hand.
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("after the time allowed the form says 'couldn't send', keeps the words, and the person can try again", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime.bind(vi) });
    const submit = vi
      .fn<Submit>()
      .mockImplementationOnce(() => new Promise(() => {})) // never answers
      .mockResolvedValueOnce({ id: 9 });
    renderForm(submit);
    await openForm(user);
    await user.type(box(), "on a bad connection");
    await user.click(sendButton());
    expect(sendButton()).toHaveTextContent("Sending…");

    await act(async () => {
      vi.advanceTimersByTime(FEEDBACK_SEND_TIMEOUT_MS - 2000);
    });
    expect(sendButton()).toHaveTextContent("Sending…"); // not yet
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    await act(async () => {
      vi.advanceTimersByTime(2500);
    });
    expect(screen.getByRole("alert")).toHaveTextContent(FEEDBACK_FAILED_TEXT);
    expect(box()).toHaveValue("on a bad connection");
    expect(sendButton()).toBeEnabled();
    expect(isFeedbackSent()).toBe(false);

    await user.click(sendButton());
    expect(await screen.findByRole("heading", { name: "Thanks — we read every message." })).toBeInTheDocument();
    expect(submit).toHaveBeenCalledTimes(2);
  });

  it("an answer that comes after the form gave up does not rewrite what it shows", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime.bind(vi) });
    const first = deferred<FeedbackReceiptDto>();
    renderForm(() => first.promise);
    await openForm(user);
    await user.type(box(), "late answer");
    await user.click(sendButton());
    await act(async () => {
      vi.advanceTimersByTime(FEEDBACK_SEND_TIMEOUT_MS);
    });
    expect(screen.getByRole("alert")).toHaveTextContent(FEEDBACK_FAILED_TEXT);

    await act(async () => first.resolve({ id: 1 }));
    expect(screen.getByRole("alert")).toHaveTextContent(FEEDBACK_FAILED_TEXT); // still what the person was told
    expect(box()).toHaveValue("late answer");
  });

  it("an answer in time cancels the wait: nothing fires 20 seconds later", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime.bind(vi) });
    renderForm(() => Promise.resolve({ id: 1 }));
    await openForm(user);
    await user.type(box(), "quick");
    await user.click(sendButton());
    await screen.findByRole("heading", { name: "Thanks — we read every message." });
    await act(async () => {
      vi.advanceTimersByTime(FEEDBACK_SEND_TIMEOUT_MS * 2);
    });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Thanks — we read every message." })).toBeInTheDocument();
  });

  it("the time allowed is long enough for a slow phone and short enough to be a wait, not a hang", () => {
    expect(FEEDBACK_SEND_TIMEOUT_MS).toBeGreaterThanOrEqual(10_000);
    expect(FEEDBACK_SEND_TIMEOUT_MS).toBeLessThanOrEqual(30_000);
  });
});

describe("closing is as easy as opening", () => {
  it("Cancel closes it", async () => {
    const user = userEvent.setup();
    renderForm();
    const dialog = await openForm(user);
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("Escape closes it — from the box, and from anywhere inside", async () => {
    const user = userEvent.setup();
    renderForm();
    await openForm(user);
    await user.type(box(), "typing{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await openForm(user);
    screen.getByRole("button", { name: "Cancel" }).focus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("a tap on the dimmed area outside closes it; a tap inside does not", async () => {
    renderForm();
    const dialog = await openForm();
    fireEvent.mouseDown(dialog);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    fireEvent.mouseDown(box());
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    fireEvent.mouseDown(dialog.parentElement!);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("Android's Back button closes it (before it would go back a screen)", async () => {
    renderForm();
    await openForm();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    act(() => {
      expect(handleBackPress()).toBe(true);
    });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(handleBackPress()).toBe(false); // closed: Back is the app's again
  });

  it("closing from the thank-you is a close too: Escape and Back work there as well", async () => {
    const user = userEvent.setup();
    renderForm();
    await openForm(user);
    await user.type(box(), "x");
    await user.click(sendButton());
    await screen.findByRole("heading", { name: "Thanks — we read every message." });
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});

describe("the person's words are not lost to a slip", () => {
  it("closing keeps what was typed for this visit: the next opening has it", async () => {
    const user = userEvent.setup();
    renderForm();
    const dialog = await openForm(user);
    await user.type(box(), "half a thought");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await openForm(user);
    expect(box()).toHaveValue("half a thought");
    expect(sendButton()).toBeEnabled();
  });

  it("a tap outside by mistake does not cost a long message", async () => {
    const user = userEvent.setup();
    renderForm();
    const dialog = await openForm(user);
    await user.type(box(), "a long, careful paragraph");
    fireEvent.mouseDown(dialog.parentElement!);
    await openForm(user);
    expect(box()).toHaveValue("a long, careful paragraph");
  });

  it("a send that finishes after the sheet was closed: its outcome — thanks — is what the next opening shows", async () => {
    const user = userEvent.setup();
    const pending = deferred<FeedbackReceiptDto>();
    renderForm(() => pending.promise, "/", { strip: true });
    await openForm(user);
    await user.type(box(), "sent, then closed");
    await user.click(sendButton());
    await user.keyboard("{Escape}"); // closed while it is on its way
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await act(async () => pending.resolve({ id: 3 }));
    expect(isFeedbackSent()).toBe(true); // it was stored all the same
    expect(document.querySelector(".feedback-strip")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "open the form" })); // not `openForm`: this dialog is named by its thanks
    expect(await screen.findByRole("heading", { name: "Thanks — we read every message." })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Close" }));
    await openForm(user);
    expect(box()).toHaveValue(""); // and after that, a fresh form
  });

  it("a send that fails after the sheet was closed: the reason, and the words, are there at the next opening", async () => {
    const user = userEvent.setup();
    const pending = deferred<FeedbackReceiptDto>();
    renderForm(() => pending.promise);
    await openForm(user);
    await user.type(box(), "closed before the answer");
    await user.click(sendButton());
    await user.keyboard("{Escape}");
    await act(async () => pending.reject(new Error("down")));
    expect(isFeedbackSent()).toBe(false);

    const dialog = await openForm(user);
    expect(within(dialog).getByRole("alert")).toHaveTextContent(FEEDBACK_FAILED_TEXT);
    expect(box()).toHaveValue("closed before the answer");
  });

  it("a refusal that was read (the sheet closed with it on screen) is not shown again next time", async () => {
    const user = userEvent.setup();
    renderForm(() => Promise.reject(new Error("down")));
    await openForm(user);
    await user.type(box(), "text");
    await user.click(sendButton());
    await screen.findByRole("alert");
    await user.keyboard("{Escape}");
    await openForm(user);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(box()).toHaveValue("text");
  });

  it("an opened-again form is not stuck 'sending' after a send that was left behind", async () => {
    const user = userEvent.setup();
    const pending = deferred<FeedbackReceiptDto>();
    renderForm(() => pending.promise);
    await openForm(user);
    await user.type(box(), "text");
    await user.click(sendButton());
    await user.keyboard("{Escape}");
    await openForm(user);
    expect(sendButton()).toHaveTextContent("Sending…"); // still true — and honest about it
    await act(async () => pending.resolve({ id: 1 }));
    expect(await screen.findByRole("heading", { name: "Thanks — we read every message." })).toBeInTheDocument();
  });
});

describe("the dialog's manners", () => {
  it("a computer: the focus goes straight into the box, and back to what had it when the form closes", async () => {
    const user = userEvent.setup();
    renderForm();
    const opener = screen.getByRole("button", { name: "open the form" });
    await user.click(opener);
    expect(box()).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(opener).toHaveFocus();
  });

  it("a touch screen: the focus goes to the sheet, so the keyboard does not come up before the person has read it", async () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn((query: string) => ({ matches: query === "(pointer: coarse)", media: query, addEventListener() {}, removeEventListener() {} })),
    );
    renderForm();
    const dialog = await openForm();
    expect(dialog).toHaveFocus();
    expect(box()).not.toHaveFocus();
  });

  it("Tab and Shift+Tab stay inside it", async () => {
    const user = userEvent.setup();
    renderForm();
    const dialog = await openForm(user);
    await user.type(box(), "x"); // Send is enabled: the loop is box → credit link → Cancel → Send
    const send = within(dialog).getByRole("button", { name: "Send" });
    send.focus();
    await user.tab();
    expect(box()).toHaveFocus(); // after the last control, back to the first
    await user.tab({ shift: true });
    expect(send).toHaveFocus(); // and the other way round
  });

  it("the page behind does not scroll while it is open, and does again when it closes", async () => {
    const user = userEvent.setup();
    renderForm();
    expect(document.body.style.overflow).toBe("");
    await openForm(user);
    expect(document.body.style.overflow).toBe("hidden");
    await user.keyboard("{Escape}");
    expect(document.body.style.overflow).toBe("");
  });

  it("the title names the dialog, and the box is a labelled field (not a placeholder pretending to be one)", async () => {
    renderForm();
    const dialog = await openForm();
    expect(dialog).toHaveAccessibleName("Feedback");
    expect(box()).toHaveAccessibleName("What did you like? What didn't work for you?");
  });
});
