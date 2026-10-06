SERVICE_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
CARGO := cargo +1.98.1

.PHONY: quality test release-check compose-check image

# Fast module-local gates. This target deliberately does not run tests.
quality:
	cd "$(SERVICE_DIR)" && $(CARGO) fmt --all -- --check
	cd "$(SERVICE_DIR)" && $(CARGO) check --all-targets --all-features --locked
	cd "$(SERVICE_DIR)" && $(CARGO) clippy --all-targets --all-features --locked -- -D warnings

test:
	cd "$(SERVICE_DIR)" && $(CARGO) test --all-targets --all-features --locked

release-check: quality
	cd "$(SERVICE_DIR)" && RUSTDOCFLAGS="-D warnings" $(CARGO) doc --no-deps --locked
	cd "$(SERVICE_DIR)" && $(CARGO) build --locked --release
	$(MAKE) compose-check

compose-check:
	docker compose -f "$(SERVICE_DIR)compose.yaml" config -q

# BuildKit image build is a separate target because it needs the Docker daemon.
image:
	docker compose -f "$(SERVICE_DIR)compose.yaml" build streaming media-adapter
