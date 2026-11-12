#!/usr/bin/env python3
"""Validate DPGrabber.xcodeproj/project.pbxproj without Xcode.

Checks:
  1. The file parses with the pbxproj library and has no missing
     references (its own _check_missing_references).
  2. Every 24-hex ID referenced in the file is defined as an object.
  3. Every .swift file under Sources/ and App/ is in the Sources phase.
  4. Every file reference resolves to a real file on disk.
  5. The target is an iOS app with the right bundle ID, deployment
     target, Info.plist path, app icon, and linked frameworks.
  6. No duplicate IDs.

Exit 0 when everything passes, 1 otherwise.
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJECT_PATH = os.path.join(ROOT, "DPGrabber.xcodeproj", "project.pbxproj")

failures = []


def check(condition, message):
    print(("PASS " if condition else "FAIL ") + message)
    if not condition:
        failures.append(message)


def main() -> int:
    try:
        from pbxproj import XcodeProject
    except ImportError:
        print("FAIL pbxproj library not installed (pip install pbxproj)")
        return 1
    project = XcodeProject.load(PROJECT_PATH)

    # --- 1. Library parse + missing-reference check ----------------------
    check(not project._check_missing_references(),
          "pbxproj library reports no missing references")

    # --- 2. Text-level ID audit -------------------------------------------
    text = open(PROJECT_PATH, encoding="utf-8").read()
    # Object definitions sit at exactly two tabs of indentation; deeper
    # lines such as TargetAttributes entries are dictionary keys, not
    # objects, and must not be counted.
    def_re = r'^\t\t([0-9A-F]{24})\s*(?:/\*.*?\*/)?\s*=\s*\{'
    def_lines = re.findall(def_re, text, re.M)
    defined = set(def_lines)
    referenced = set(re.findall(r'\b([0-9A-F]{24})\b', text))
    check(referenced <= defined,
          "every referenced ID is defined (undefined: %s)" % sorted(referenced - defined))
    check(len(def_lines) == len(defined),
          "no duplicate object IDs (%d objects)" % len(defined))

    # --- Target ------------------------------------------------------------
    targets = project.objects.get_objects_in_section("PBXNativeTarget")
    check(len(targets) == 1, "exactly one native target")
    target = targets[0] if targets else None
    if target is None:
        print("FAIL no target found")
        return 1
    check(target.name == "DPGrabber", "target named DPGrabber")
    check(target.productType == "com.apple.product-type.application",
          "target is an application")

    # --- 3. Sources phase covers every .swift file on disk ----------------
    disk_swift = {}
    for sub in ("Sources/DPGrabberCore", "App"):
        for name in os.listdir(os.path.join(ROOT, sub)):
            if name.endswith(".swift"):
                disk_swift[name] = os.path.join(sub, name)
    phase_names = set()
    for phase in project.objects.get_objects_in_section("PBXSourcesBuildPhase"):
        for bf_key in phase.get("files", []):
            bf = project.get_object(bf_key)
            if bf is None:
                continue
            ref = project.get_object(bf.fileRef)
            if ref is not None and ref.path:
                phase_names.add(ref.path)
    check(set(disk_swift) == phase_names,
          "sources phase covers all %d swift files on disk (missing: %s, extra: %s)"
          % (len(disk_swift), sorted(set(disk_swift) - phase_names),
             sorted(phase_names - set(disk_swift))))

    # --- 4. Every file reference exists on disk ----------------------------
    # Build a parent map from group children.
    parent = {}
    for group in project.objects.get_objects_in_section("PBXGroup"):
        for child_key in group.get("children", []):
            parent[str(child_key)] = group
    missing = []
    for ref in project.objects.get_objects_in_section("PBXFileReference"):
        if ref.sourceTree == "SDKROOT" or ref.sourceTree == "BUILT_PRODUCTS_DIR":
            continue
        if not ref.path:
            continue
        parts = [ref.path]
        group = parent.get(str(ref.get_id()))
        while group is not None and getattr(group, "path", None):
            parts.append(group.path)
            group = parent.get(str(group.get_id()))
        rel = os.path.join(*reversed(parts))
        if not os.path.exists(os.path.join(ROOT, rel)):
            missing.append(rel)
    check(not missing, "all file references exist on disk (missing: %s)" % missing)

    # --- 5. Build settings --------------------------------------------------
    tgt_list = project.get_object(target.buildConfigurationList)
    tgt_settings = {}
    for cfg_key in tgt_list.buildConfigurations:
        cfg = project.get_object(cfg_key)
        tgt_settings[cfg.name] = cfg.buildSettings
    rel = tgt_settings.get("Release", {})
    check(rel.get("PRODUCT_BUNDLE_IDENTIFIER", None) == "com.doimty.dpgrabber",
          "bundle id com.doimty.dpgrabber")
    # IPHONEOS_DEPLOYMENT_TARGET lives on the project-level configs
    # (target configs inherit it); check there.
    proj = [o for o in project.objects.get_objects_in_section("PBXProject")][0]
    proj_list = project.get_object(proj.buildConfigurationList)
    proj_settings = {}
    for cfg_key in proj_list.buildConfigurations:
        cfg = project.get_object(cfg_key)
        proj_settings[cfg.name] = cfg.buildSettings
    check(proj_settings.get("Release", {}).get("IPHONEOS_DEPLOYMENT_TARGET", None) == "15.0",
          "deployment target 15.0 (project release config)")
    check(rel.get("INFOPLIST_FILE", None) == "App/Info.plist",
          "Info.plist path App/Info.plist")
    check(rel.get("ASSETCATALOG_COMPILER_APPICON_NAME", None) == "AppIcon",
          "app icon AppIcon")
    check(rel.get("CODE_SIGN_STYLE", None) == "Manual",
          "manual code signing (unsigned CI build)")
    check(rel.get("SWIFT_VERSION", None) == "5.0", "Swift 5.0")
    check(rel.get("TARGETED_DEVICE_FAMILY", None) == "1", "iPhone only")

    # --- Frameworks ---------------------------------------------------------
    fw_names = set()
    for phase in project.objects.get_objects_in_section("PBXFrameworksBuildPhase"):
        for bf_key in phase.get("files", []):
            bf = project.get_object(bf_key)
            if bf is None:
                continue
            ref = project.get_object(bf.fileRef)
            if ref is not None:
                fw_names.add(ref.name or ref.path)
    for needed in ("AVFoundation.framework", "Security.framework",
                   "UserNotifications.framework"):
        check(needed in fw_names, "links %s" % needed)

    # --- Resources -----------------------------------------------------------
    res_names = set()
    for phase in project.objects.get_objects_in_section("PBXResourcesBuildPhase"):
        for bf_key in phase.get("files", []):
            bf = project.get_object(bf_key)
            if bf is None:
                continue
            ref = project.get_object(bf.fileRef)
            if ref is not None:
                res_names.add(ref.path)
    check("Assets.xcassets" in res_names, "resources include Assets.xcassets")
    check("LaunchScreen.storyboard" in res_names,
          "resources include LaunchScreen.storyboard")

    print("\n%d check(s) failed" % len(failures) if failures else "\nall checks passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
