fn main() {
    // SQLx embeds migrations at compile time. Track directory additions as well
    // as changes to existing files, including when Cargo's target is cached.
    println!("cargo:rerun-if-changed=migrations");
    println!("cargo:rerun-if-changed=media-adapter/migrations");
}
