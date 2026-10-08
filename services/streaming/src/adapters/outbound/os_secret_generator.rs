use std::future::Future;

use crate::application::ports::stream_config::{SecretGenerationError, SecretGenerator};

#[derive(Clone, Copy, Default)]
pub struct OsSecretGenerator;

impl SecretGenerator for OsSecretGenerator {
    fn generate_stream_key(
        &self,
    ) -> impl Future<Output = Result<String, SecretGenerationError>> + Send {
        async move {
            let mut secret = [0_u8; 32];
            getrandom::fill(&mut secret).map_err(|_| SecretGenerationError)?;
            let mut encoded = String::with_capacity(67);
            encoded.push_str("sk_");
            for byte in secret {
                use std::fmt::Write;
                write!(&mut encoded, "{byte:02x}").map_err(|_| SecretGenerationError)?;
            }
            Ok(encoded)
        }
    }
}
