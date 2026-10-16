import io
import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_debug_apk import ApkVerificationError, REQUIRED_ENTRIES, inspect_aapt_output, inspect_archive


class VerifyDebugApkTest(unittest.TestCase):
    def make_apk(self, root: Path, *, missing: str | None = None, invalid_project: bool = False) -> Path:
        apk_path = root / "debug.apk"
        project = io.BytesIO()
        if not invalid_project:
            with zipfile.ZipFile(project, "w") as project_zip:
                project_zip.writestr("code.xml", "<program/>")

        entries = set(REQUIRED_ENTRIES) | {"classes.dex"}
        if missing:
            entries.discard(missing)

        with zipfile.ZipFile(apk_path, "w") as apk:
            for name in sorted(entries):
                content = b"entry"
                if name == "assets/project.zip":
                    content = project.getvalue() if not invalid_project else b"not a zip"
                apk.writestr(name, content)
        return apk_path

    def test_archive_verification_requires_launcher_assets_native_libs_and_bundled_project(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            report = inspect_archive(self.make_apk(Path(temporary_directory)))

        self.assertEqual(report["entries"], len(REQUIRED_ENTRIES) + 1)
        self.assertEqual(report["dex_files"], ["classes.dex"])
        self.assertIn("libcatroid.so", report["arm64_libraries"])
        self.assertIn("libpython3.12.so", report["arm64_libraries"])

    def test_archive_verification_fails_when_core_native_library_is_missing(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            apk_path = self.make_apk(Path(temporary_directory), missing="lib/arm64-v8a/libcatroid.so")
            with self.assertRaisesRegex(ApkVerificationError, "libcatroid.so"):
                inspect_archive(apk_path)

    def test_archive_verification_rejects_invalid_bundled_project_zip(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            apk_path = self.make_apk(Path(temporary_directory), invalid_project=True)
            with self.assertRaisesRegex(ApkVerificationError, "not a valid ZIP"):
                inspect_archive(apk_path)

    def test_aapt_verification_requires_expected_package_launcher_and_layouts(self):
        badging = """package: name='org.DanVexTeam.NewCatroid.ci' versionCode='132'\nlaunchable-activity: name='org.catrobat.catroid.ui.MainMenuActivity' label='NewCatroid'\n"""
        resources = "layout/activity_main_menu layout/activity_main_menu_splashscreen layout/activity_loading layout/fragment_main_menu layout/fragment_main_menu_pc drawable/ic_launcher_foreground"
        result = inspect_aapt_output(badging, resources)
        self.assertEqual(result["package"], "org.DanVexTeam.NewCatroid.ci")
        self.assertEqual(result["launcher"], "org.catrobat.catroid.ui.MainMenuActivity")

    def test_aapt_verification_rejects_an_unexpected_launcher(self):
        badging = "package: name='org.DanVexTeam.NewCatroid'\nlaunchable-activity: name='org.catrobat.catroid.ui.WebViewActivity'\n"
        with self.assertRaisesRegex(ApkVerificationError, "Expected launcher"):
            inspect_aapt_output(badging, " ".join(
                ["activity_main_menu", "activity_main_menu_splashscreen", "activity_loading", "fragment_main_menu", "fragment_main_menu_pc", "ic_launcher_foreground"]
            ))

    def test_aapt_verification_rejects_missing_compiled_ui_resource(self):
        badging = "package: name='org.DanVexTeam.NewCatroid'\nlaunchable-activity: name='org.catrobat.catroid.ui.MainMenuActivity'\n"
        with self.assertRaisesRegex(ApkVerificationError, "activity_loading"):
            inspect_aapt_output(badging, "activity_main_menu fragment_main_menu ic_launcher_foreground")


if __name__ == "__main__":
    unittest.main()
