#!/bin/bash

# Quick build script for testing the peer callback implementation

set -e

echo "======================================"
echo "Building Peer Callback Implementation"
echo "======================================"
echo ""

# Step 1: Test yggdrasil-go
echo "Step 1/3: Testing yggdrasil-go..."
cd lib/yggdrasil-go/src/core
go test -run TestPeerChange -v
echo "✓ Tests passed"
echo ""

# Step 2: Build yggstack.aar
echo "Step 2/3: Building yggstack.aar (this takes several minutes)..."
cd ../../yggstack
./build-android.sh
echo "✓ yggstack.aar built"
echo ""

# Step 3: Build Android app
echo "Step 3/3: Building Android app..."
cd ../..
./gradlew assembleDebug --console=plain
echo "✓ Android app built"
echo ""

echo "======================================"
echo "Build Complete!"
echo "======================================"
echo ""
echo "Next steps:"
echo "  1. Install on device: ./gradlew installDebug"
echo "  2. Test peer connection scenarios"
echo "  3. Verify notification icon changes"
echo ""
