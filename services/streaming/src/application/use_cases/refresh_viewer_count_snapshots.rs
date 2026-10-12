use crate::application::ports::viewer_count_snapshots::{
    ViewerCountSnapshotError, ViewerCountSnapshotRepository,
};

pub struct RefreshViewerCountSnapshots<R> {
    repository: R,
}

impl<R> RefreshViewerCountSnapshots<R> {
    pub fn new(repository: R) -> Self {
        Self { repository }
    }
}

impl<R> RefreshViewerCountSnapshots<R>
where
    R: ViewerCountSnapshotRepository,
{
    pub async fn run(&self) -> Result<(), ViewerCountSnapshotError> {
        self.repository.refresh_due_snapshots().await
    }
}
