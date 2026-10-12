use std::future::Future;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ViewerCountSnapshotError;

pub trait ViewerCountSnapshotRepository: Send + Sync {
    fn refresh_due_snapshots(
        &self,
    ) -> impl Future<Output = Result<(), ViewerCountSnapshotError>> + Send;
}
