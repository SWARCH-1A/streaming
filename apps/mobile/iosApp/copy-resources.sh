#!/bin/sh
set -eu
resources="$SRCROOT/../shared/build/generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"
destination="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/compose-resources/composeResources/streaming.mobile.resources"
mkdir -p "$destination"
ditto "$resources" "$destination"
