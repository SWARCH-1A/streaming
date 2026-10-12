use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};

/// Tracks whether every registered background worker is still running.
pub(crate) struct BackgroundWorkerHealth {
    expected_workers: AtomicUsize,
    active_workers: AtomicUsize,
}

impl BackgroundWorkerHealth {
    pub(crate) fn new() -> Self {
        Self {
            expected_workers: AtomicUsize::new(0),
            active_workers: AtomicUsize::new(0),
        }
    }

    pub(crate) fn register_worker(&self) {
        self.expected_workers.fetch_add(1, Ordering::Relaxed);
    }

    pub(crate) fn enter_worker(self: &Arc<Self>) -> ActiveWorker {
        self.active_workers.fetch_add(1, Ordering::Relaxed);
        ActiveWorker {
            health: Arc::clone(self),
        }
    }

    pub(crate) fn all_registered_workers_active(&self) -> bool {
        let expected = self.expected_workers.load(Ordering::Relaxed);
        expected > 0 && self.active_workers.load(Ordering::Relaxed) == expected
    }
}

#[must_use = "dropping this guard marks the worker as inactive"]
pub(crate) struct ActiveWorker {
    health: Arc<BackgroundWorkerHealth>,
}

impl Drop for ActiveWorker {
    fn drop(&mut self) {
        self.health.active_workers.fetch_sub(1, Ordering::Relaxed);
    }
}
