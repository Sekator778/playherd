//! Captures what the code under test logs (`tracing`), for tests that assert on the level and the
//! wording of a line -- "this is an INFO, not a WARN" is a requirement here
//! (`docs/research/chain_service.md` §17.5), and a log line is the only place it is visible.
//!
//! The subscriber is installed for the current thread only (`tracing::subscriber::set_default`), so
//! tests running in parallel do not see each other's lines; `#[tokio::test]` runs the test on one
//! thread, so everything the awaited code logs lands in the buffer.

#![allow(dead_code)]

use std::io;
use std::sync::{Arc, Mutex};

use tracing::subscriber::DefaultGuard;

#[derive(Clone, Default)]
pub struct Captured(Arc<Mutex<Vec<u8>>>);

struct Writer(Arc<Mutex<Vec<u8>>>);

impl io::Write for Writer {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(buf);
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

impl Captured {
    /// Everything logged so far, one event per line.
    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.0.lock().unwrap()).into_owned()
    }

    /// The lines that contain `needle` (a level such as `WARN`, or part of a message).
    pub fn lines_containing(&self, needle: &str) -> Vec<String> {
        self.text().lines().filter(|line| line.contains(needle)).map(str::to_string).collect()
    }
}

/// Starts capturing at TRACE level, no colours. Keep the guard alive for as long as lines are wanted.
pub fn capture() -> (DefaultGuard, Captured) {
    let captured = Captured::default();
    let sink = captured.0.clone();
    let subscriber = tracing_subscriber::fmt()
        .with_max_level(tracing::Level::TRACE)
        .with_ansi(false)
        .with_writer(move || Writer(sink.clone()))
        .finish();
    (tracing::subscriber::set_default(subscriber), captured)
}
