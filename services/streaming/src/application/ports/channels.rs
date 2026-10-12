use std::future::Future;

#[derive(Clone, Debug)]
pub struct ChannelOwner {
    pub channel_id: String,
    pub owner_user_id: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ChannelsError {
    NotFound,
    Unavailable,
    InvalidResponse,
}

pub trait ChannelsGateway: Send + Sync {
    fn find_owner_channel(
        &self,
        owner_user_id: String,
    ) -> impl Future<Output = Result<Option<ChannelOwner>, ChannelsError>> + Send;
}
