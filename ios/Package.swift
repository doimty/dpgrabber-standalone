// swift-tools-version: 5.9
// DPGrabber iOS - SwiftPM package for the pure-logic core.
//
// The Xcode project references the same Sources/DPGrabberCore files, so
// there is a single copy of every core file. This package exists so the
// core (and its tests) can be type-checked and tested on Linux, where
// there is no Xcode.
//
//   swift test

import PackageDescription

let package = Package(
    name: "DPGrabberCore",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "DPGrabberCore", targets: ["DPGrabberCore"]),
    ],
    targets: [
        .target(
            name: "DPGrabberCore",
            path: "Sources/DPGrabberCore"
        ),
        .testTarget(
            name: "DPGrabberCoreTests",
            dependencies: ["DPGrabberCore"],
            path: "Tests/DPGrabberCoreTests"
        ),
    ]
)
