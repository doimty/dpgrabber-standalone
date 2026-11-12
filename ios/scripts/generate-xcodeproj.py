#!/usr/bin/env python3
"""Generate DPGrabber.xcodeproj/project.pbxproj for the iOS standalone app.

The project is hand-generated (no Xcode on the Linux build machine) with
deterministic 24-char hex IDs derived from each object's path/name, so
re-running this script produces a byte-identical file. Validate it with:

    python3 scripts/check-xcodeproj.py

Layout (project root = ios/):
    DPGrabber.xcodeproj/project.pbxproj
    Sources/DPGrabberCore/*.swift   (shared with the SwiftPM core target)
    App/*.swift, App/Info.plist, App/LaunchScreen.storyboard,
    App/Assets.xcassets
One app target "DPGrabber" (bundle com.doimty.dpgrabber, iOS 15.0),
unsigned by default (CODE_SIGNING_ALLOWED=NO in CI).
"""

import hashlib
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "DPGrabber.xcodeproj")
OUT_PATH = os.path.join(OUT_DIR, "project.pbxproj")

CORE_FILES = [
    "AES.swift", "ApiClient.swift", "Config.swift", "Crypto.swift",
    "GrabTiming.swift", "ListenEngine.swift", "Models.swift", "OrderFilter.swift",
]
APP_FILES = [
    "AppViewModel.swift", "AudioKeepAlive.swift", "ContentView.swift",
    "CredentialStore.swift", "DPGrabberApp.swift", "LogStore.swift",
    "SettingsView.swift", "VoiceAnnouncer.swift",
]
FRAMEWORKS = ["AVFoundation.framework", "Security.framework", "UserNotifications.framework"]


def obj_id(seed: str) -> str:
    return hashlib.md5(seed.encode("utf-8")).hexdigest()[:24].upper()


def q(value: str) -> str:
    """Quote a plist string when it needs quoting.

    OpenStep bare strings may only contain A-Za-z0-9 _ $ / : . -
    (Xcode's CFPropertyList parser rejects anything else unquoted, e.g.
    the '+' in 'gnu++20' breaks the project with 'missing semicolon').
    Quote anything outside that set.
    """
    if value == "":
        return '""'
    if not re.fullmatch(r"[A-Za-z0-9_$/:.\-]+", value):
        return '"%s"' % value.replace("\\", "\\\\").replace('"', '\\"')
    return value


def build_settings(pairs, indent="\t\t\t"):
    lines = []
    for key, value in pairs:
        lines.append("%s%s = %s;" % (indent, key, q(value)))
    return "\n".join(lines)


def main() -> int:
    ids = {}
    def oid(seed):
        ids.setdefault(seed, obj_id(seed))
        return ids[seed]

    # --- Object IDs ------------------------------------------------------
    project = oid("PBXProject:DPGrabber")
    main_group = oid("PBXGroup:main")
    sources_group = oid("PBXGroup:Sources")
    core_group = oid("PBXGroup:Sources/DPGrabberCore")
    app_group = oid("PBXGroup:App")
    products_group = oid("PBXGroup:Products")
    target = oid("PBXNativeTarget:DPGrabber")
    product_ref = oid("PBXFileReference:DPGrabber.app")
    sources_phase = oid("PBXSourcesBuildPhase:DPGrabber")
    frameworks_phase = oid("PBXFrameworksBuildPhase:DPGrabber")
    resources_phase = oid("PBXResourcesBuildPhase:DPGrabber")
    proj_cfg_list = oid("XCConfigurationList:project")
    proj_cfg_debug = oid("XCBuildConfiguration:project:Debug")
    proj_cfg_release = oid("XCBuildConfiguration:project:Release")
    tgt_cfg_list = oid("XCConfigurationList:target")
    tgt_cfg_debug = oid("XCBuildConfiguration:target:Debug")
    tgt_cfg_release = oid("XCBuildConfiguration:target:Release")

    build_files = {}   # seed -> id
    file_refs = {}     # seed -> id
    for name in CORE_FILES:
        file_refs["core:" + name] = oid("PBXFileReference:Sources/DPGrabberCore/" + name)
        build_files["core:" + name] = oid("PBXBuildFile:Sources/DPGrabberCore/" + name)
    for name in APP_FILES:
        file_refs["app:" + name] = oid("PBXFileReference:App/" + name)
        build_files["app:" + name] = oid("PBXBuildFile:App/" + name)
    for name in ("Assets.xcassets", "Info.plist", "LaunchScreen.storyboard"):
        file_refs["app:" + name] = oid("PBXFileReference:App/" + name)
        build_files["app:" + name] = oid("PBXBuildFile:App/" + name)
    for name in FRAMEWORKS:
        file_refs["fw:" + name] = oid("PBXFileReference:System/" + name)
        build_files["fw:" + name] = oid("PBXBuildFile:System/" + name)

    def ref_list(keys):
        return ",\n".join("\t\t\t\t%s /* %s */" % (file_refs[k], k.split(":", 1)[1]) for k in keys) + ","

    def build_list(keys):
        return ",\n".join("\t\t\t\t%s /* %s */" % (build_files[k], k.split(":", 1)[1]) for k in keys) + ","

    core_keys = ["core:" + n for n in CORE_FILES]
    app_keys = ["app:" + n for n in APP_FILES]
    resource_keys = ["app:Assets.xcassets", "app:LaunchScreen.storyboard"]
    fw_keys = ["fw:" + n for n in FRAMEWORKS]

    # --- Sections --------------------------------------------------------
    sections = []

    # PBXBuildFile
    lines = ["/* Begin PBXBuildFile section */"]
    for key in core_keys + app_keys + resource_keys + fw_keys:
        name = key.split(":", 1)[1]
        lines.append("\t\t%s /* %s in %s */ = {isa = PBXBuildFile; fileRef = %s /* %s */; };" % (
            build_files[key], name,
            "Sources" if name.endswith(".swift") else ("Resources" if key in resource_keys else "Frameworks"),
            file_refs[key], name))
    lines.append("/* End PBXBuildFile section */")
    sections.append("\n".join(lines))

    # PBXFileReference
    lines = ["/* Begin PBXFileReference section */"]
    for key in core_keys:
        name = key.split(":", 1)[1]
        lines.append('\t\t%s /* %s */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = %s; sourceTree = "<group>"; };' % (
            file_refs[key], name, q(name)))
    for key in app_keys:
        name = key.split(":", 1)[1]
        lines.append('\t\t%s /* %s */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = %s; sourceTree = "<group>"; };' % (
            file_refs[key], name, q(name)))
    lines.append('\t\t%s /* Assets.xcassets */ = {isa = PBXFileReference; lastKnownFileType = folder.assetcatalog; path = Assets.xcassets; sourceTree = "<group>"; };' % file_refs["app:Assets.xcassets"])
    lines.append('\t\t%s /* Info.plist */ = {isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = "<group>"; };' % file_refs["app:Info.plist"])
    lines.append('\t\t%s /* LaunchScreen.storyboard */ = {isa = PBXFileReference; lastKnownFileType = file.storyboard; path = LaunchScreen.storyboard; sourceTree = "<group>"; };' % file_refs["app:LaunchScreen.storyboard"])
    for name in FRAMEWORKS:
        lines.append('\t\t%s /* %s */ = {isa = PBXFileReference; lastKnownFileType = wrapper.framework; name = %s; path = %s; sourceTree = SDKROOT; };' % (
            file_refs["fw:" + name], name, q(name), q("System/Library/Frameworks/" + name)))
    lines.append('\t\t%s /* DPGrabber.app */ = {isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = DPGrabber.app; sourceTree = BUILT_PRODUCTS_DIR; };' % product_ref)
    lines.append("/* End PBXFileReference section */")
    sections.append("\n".join(lines))

    # PBXFrameworksBuildPhase
    sections.append("\n".join([
        "/* Begin PBXFrameworksBuildPhase section */",
        "\t\t%s /* Frameworks */ = {" % frameworks_phase,
        "\t\t\tisa = PBXFrameworksBuildPhase;",
        "\t\t\tbuildActionMask = 2147483647;",
        "\t\t\tfiles = (",
        build_list(fw_keys),
        "\t\t\t);",
        "\t\t\trunOnlyForDeploymentPostprocessing = 0;",
        "\t\t};",
        "/* End PBXFrameworksBuildPhase section */",
    ]))

    # PBXGroup
    sections.append("\n".join([
        "/* Begin PBXGroup section */",
        "\t\t%s = {" % main_group,
        "\t\t\tisa = PBXGroup;",
        "\t\t\tchildren = (",
        "\t\t\t\t%s /* Sources */," % sources_group,
        "\t\t\t\t%s /* App */," % app_group,
        "\t\t\t\t%s /* Products */," % products_group,
        "\t\t\t);",
        "\t\t\tsourceTree = \"<group>\";",
        "\t\t};",
        "\t\t%s /* Sources */ = {" % sources_group,
        "\t\t\tisa = PBXGroup;",
        "\t\t\tchildren = (",
        "\t\t\t\t%s /* DPGrabberCore */," % core_group,
        "\t\t\t);",
        "\t\t\tpath = Sources;",
        "\t\t\tsourceTree = \"<group>\";",
        "\t\t};",
        "\t\t%s /* DPGrabberCore */ = {" % core_group,
        "\t\t\tisa = PBXGroup;",
        "\t\t\tchildren = (",
        ref_list(core_keys),
        "\t\t\t);",
        "\t\t\tpath = DPGrabberCore;",
        "\t\t\tsourceTree = \"<group>\";",
        "\t\t};",
        "\t\t%s /* App */ = {" % app_group,
        "\t\t\tisa = PBXGroup;",
        "\t\t\tchildren = (",
        ref_list(app_keys + ["app:Assets.xcassets", "app:Info.plist", "app:LaunchScreen.storyboard"]),
        "\t\t\t);",
        "\t\t\tpath = App;",
        "\t\t\tsourceTree = \"<group>\";",
        "\t\t};",
        "\t\t%s /* Products */ = {" % products_group,
        "\t\t\tisa = PBXGroup;",
        "\t\t\tchildren = (",
        "\t\t\t\t%s /* DPGrabber.app */," % product_ref,
        "\t\t\t);",
        "\t\t\tname = Products;",
        "\t\t\tsourceTree = \"<group>\";",
        "\t\t};",
        "/* End PBXGroup section */",
    ]))

    # PBXNativeTarget
    sections.append("\n".join([
        "/* Begin PBXNativeTarget section */",
        "\t\t%s /* DPGrabber */ = {" % target,
        "\t\t\tisa = PBXNativeTarget;",
        "\t\t\tbuildConfigurationList = %s /* Build configuration list for PBXNativeTarget \"DPGrabber\" */;" % tgt_cfg_list,
        "\t\t\tbuildPhases = (",
        "\t\t\t\t%s /* Sources */," % sources_phase,
        "\t\t\t\t%s /* Frameworks */," % frameworks_phase,
        "\t\t\t\t%s /* Resources */," % resources_phase,
        "\t\t\t);",
        "\t\t\tbuildRules = (",
        "\t\t\t);",
        "\t\t\tdependencies = (",
        "\t\t\t);",
        "\t\t\tname = DPGrabber;",
        "\t\t\tproductName = DPGrabber;",
        "\t\t\tproductReference = %s /* DPGrabber.app */;" % product_ref,
        "\t\t\tproductType = \"com.apple.product-type.application\";",
        "\t\t};",
        "/* End PBXNativeTarget section */",
    ]))

    # PBXProject
    sections.append("\n".join([
        "/* Begin PBXProject section */",
        "\t\t%s /* Project object */ = {" % project,
        "\t\t\tisa = PBXProject;",
        "\t\t\tattributes = {",
        "\t\t\t\tLastSwiftUpdateCheck = 1500;",
        "\t\t\t\tLastUpgradeCheck = 1500;",
        "\t\t\t\tTargetAttributes = {",
        "\t\t\t\t\t%s = {" % target,
        "\t\t\t\t\t\tCreatedOnToolsVersion = 15.0;",
        "\t\t\t\t\t};",
        "\t\t\t\t};",
        "\t\t\t};",
        "\t\t\tbuildConfigurationList = %s /* Build configuration list for PBXProject \"DPGrabber\" */;" % proj_cfg_list,
        "\t\t\tcompatibilityVersion = \"Xcode 14.0\";",
        "\t\t\tdevelopmentRegion = zh_CN;",
        "\t\t\thasScannedForEncodings = 0;",
        "\t\t\tknownRegions = (",
        "\t\t\t\ten,",
        "\t\t\t\tBase,",
        "\t\t\t\tzh_CN,",
        "\t\t\t);",
        "\t\t\tmainGroup = %s;" % main_group,
        "\t\t\tproductRefGroup = %s /* Products */;" % products_group,
        "\t\t\tprojectDirPath = \"\";",
        "\t\t\tprojectRoot = \"\";",
        "\t\t\ttargets = (",
        "\t\t\t\t%s /* DPGrabber */," % target,
        "\t\t\t);",
        "\t\t};",
        "/* End PBXProject section */",
    ]))

    # PBXResourcesBuildPhase
    sections.append("\n".join([
        "/* Begin PBXResourcesBuildPhase section */",
        "\t\t%s /* Resources */ = {" % resources_phase,
        "\t\t\tisa = PBXResourcesBuildPhase;",
        "\t\t\tbuildActionMask = 2147483647;",
        "\t\t\tfiles = (",
        build_list(resource_keys),
        "\t\t\t);",
        "\t\t\trunOnlyForDeploymentPostprocessing = 0;",
        "\t\t};",
        "/* End PBXResourcesBuildPhase section */",
    ]))

    # PBXSourcesBuildPhase
    sections.append("\n".join([
        "/* Begin PBXSourcesBuildPhase section */",
        "\t\t%s /* Sources */ = {" % sources_phase,
        "\t\t\tisa = PBXSourcesBuildPhase;",
        "\t\t\tbuildActionMask = 2147483647;",
        "\t\t\tfiles = (",
        build_list(core_keys + app_keys),
        "\t\t\t);",
        "\t\t\trunOnlyForDeploymentPostprocessing = 0;",
        "\t\t};",
        "/* End PBXSourcesBuildPhase section */",
    ]))

    # XCBuildConfiguration
    project_common = [
        ("ALWAYS_SEARCH_USER_PATHS", "NO"),
        ("CLANG_ANALYZER_NONNULL", "YES"),
        ("CLANG_ANALYZER_NUMBER_OBJECT_CONVERSION", "YES_AGGRESSIVE"),
        ("CLANG_CXX_LANGUAGE_STANDARD", "gnu++20"),
        ("CLANG_ENABLE_MODULES", "YES"),
        ("CLANG_ENABLE_OBJC_ARC", "YES"),
        ("CLANG_ENABLE_OBJC_WEAK", "YES"),
        ("CLANG_WARN_DOCUMENTATION_COMMENTS", "YES"),
        ("CLANG_WARN_UNGUARDED_AVAILABILITY", "YES_AGGRESSIVE"),
        ("COPY_PHASE_STRIP", "NO"),
        ("DEBUG_INFORMATION_FORMAT", "dwarf"),
        ("ENABLE_STRICT_OBJC_MSGSEND", "YES"),
        ("ENABLE_TESTABILITY", "YES"),
        ("GCC_C_LANGUAGE_STANDARD", "gnu17"),
        ("GCC_NO_COMMON_BLOCKS", "YES"),
        ("GCC_WARN_UNINITIALIZED_AUTOS", "YES_AGGRESSIVE"),
        ("IPHONEOS_DEPLOYMENT_TARGET", "15.0"),
        ("LOCALIZATION_PREFS_STRING", "zh_CN"),
        ("MTL_ENABLE_DEBUG_INFO", "INCLUDE_SOURCE"),
        ("MTL_FAST_MATH", "YES"),
        ("ONLY_ACTIVE_ARCH", "YES"),
        ("SDKROOT", "iphoneos"),
        ("SWIFT_VERSION", "5.0"),
    ]
    project_release = [
        ("DEBUG_INFORMATION_FORMAT", "dwarf-with-dsym"),
        ("ENABLE_NS_ASSERTIONS", "NO"),
        ("MTL_ENABLE_DEBUG_INFO", "NO"),
        ("SWIFT_OPTIMIZATION_LEVEL", "-O"),
        ("SWIFT_COMPILATION_MODE", "wholemodule"),
        ("VALIDATE_PRODUCT", "YES"),
    ]
    target_common = [
        ("ASSETCATALOG_COMPILER_APPICON_NAME", "AppIcon"),
        ("CODE_SIGN_STYLE", "Manual"),
        ("CURRENT_PROJECT_VERSION", "1"),
        ("DEVELOPMENT_TEAM", ""),
        ("GENERATE_INFOPLIST_FILE", "NO"),
        ("INFOPLIST_FILE", "App/Info.plist"),
        ("LD_RUNPATH_SEARCH_PATHS", "$(inherited) @executable_path/Frameworks"),
        ("MARKETING_VERSION", "1.0.0"),
        ("PRODUCT_BUNDLE_IDENTIFIER", "com.doimty.dpgrabber"),
        ("PRODUCT_NAME", "$(TARGET_NAME)"),
        ("SUPPORTED_PLATFORMS", "iphoneos"),
        ("SWIFT_VERSION", "5.0"),
        ("TARGETED_DEVICE_FAMILY", "1"),
    ]
    target_debug = [
        ("DEBUG_INFORMATION_FORMAT", "dwarf"),
        ("MTL_ENABLE_DEBUG_INFO", "INCLUDE_SOURCE"),
        ("MTL_FAST_MATH", "YES"),
        ("ONLY_ACTIVE_ARCH", "YES"),
        ("SWIFT_ACTIVE_COMPILATION_CONDITIONS", "DEBUG"),
        ("SWIFT_OPTIMIZATION_LEVEL", "-Onone"),
    ]
    target_release = [
        ("DEBUG_INFORMATION_FORMAT", "dwarf-with-dsym"),
        ("MTL_ENABLE_DEBUG_INFO", "NO"),
        ("SWIFT_COMPILATION_MODE", "wholemodule"),
        ("SWIFT_OPTIMIZATION_LEVEL", "-O"),
        ("VALIDATE_PRODUCT", "YES"),
    ]
    lines = ["/* Begin XCBuildConfiguration section */"]
    for cfg_id, name, pairs in (
        (proj_cfg_debug, "Debug", project_common),
        (proj_cfg_release, "Release", project_common + project_release),
        (tgt_cfg_debug, "Debug", target_common + target_debug),
        (tgt_cfg_release, "Release", target_common + target_release),
    ):
        lines.append("\t\t%s /* %s */ = {" % (cfg_id, name))
        lines.append("\t\t\tisa = XCBuildConfiguration;")
        lines.append("\t\t\tbuildSettings = {")
        lines.append(build_settings(pairs))
        lines.append("\t\t\t};")
        lines.append("\t\t\tname = %s;" % name)
        lines.append("\t\t};")
    lines.append("/* End XCBuildConfiguration section */")
    sections.append("\n".join(lines))

    # XCConfigurationList
    sections.append("\n".join([
        "/* Begin XCConfigurationList section */",
        "\t\t%s /* Build configuration list for PBXProject \"DPGrabber\" */ = {" % proj_cfg_list,
        "\t\t\tisa = XCConfigurationList;",
        "\t\t\tbuildConfigurations = (",
        "\t\t\t\t%s /* Debug */," % proj_cfg_debug,
        "\t\t\t\t%s /* Release */," % proj_cfg_release,
        "\t\t\t);",
        "\t\t\tdefaultConfigurationIsVisible = 0;",
        "\t\t\tdefaultConfigurationName = Release;",
        "\t\t};",
        "\t\t%s /* Build configuration list for PBXNativeTarget \"DPGrabber\" */ = {" % tgt_cfg_list,
        "\t\t\tisa = XCConfigurationList;",
        "\t\t\tbuildConfigurations = (",
        "\t\t\t\t%s /* Debug */," % tgt_cfg_debug,
        "\t\t\t\t%s /* Release */," % tgt_cfg_release,
        "\t\t\t);",
        "\t\t\tdefaultConfigurationIsVisible = 0;",
        "\t\t\tdefaultConfigurationName = Release;",
        "\t\t};",
        "/* End XCConfigurationList section */",
    ]))

    header = """// !$*UTF8*$!
{
\tarchiveVersion = 1;
\tclasses = {
\t};
\tobjectVersion = 56;
\tobjects = {
"""
    footer = """\t};
\trootObject = %s /* Project object */;
}
""" % project

    os.makedirs(OUT_DIR, exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        f.write(header)
        f.write("\n".join(sections))
        f.write("\n")
        f.write(footer)
    print("wrote %s (%d bytes)" % (OUT_PATH, os.path.getsize(OUT_PATH)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
