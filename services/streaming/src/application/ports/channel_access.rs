use std::{future::Future, pin::Pin};

#[derive(Clone, Debug)]
pub struct ChannelAccess {
    pub owner_user_id: String,
    pub account_version: i64,
    pub active: bool,
}

/// Current authority from Core; unavailable is never equivalent to active.
pub trait ChannelAccessGateway: Send + Sync {
    fn channel_access(
        &self,
        channel_id: String,
    ) -> Pin<Box<dyn Future<Output = Result<ChannelAccess, ()>> + Send + '_>>;
}
