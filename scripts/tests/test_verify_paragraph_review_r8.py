"""测试段评 release R8 mapping 校验器的成功与失败边界。"""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import verify_paragraph_review_r8 as verifier  # noqa: E402


class VerifyParagraphReviewR8Test(unittest.TestCase):
    """验证校验器能稳定识别 release mapping 的关键回归。"""

    def build_mapping(
        self,
        *,
        omitted_class: str | None = None,
        renamed_class: tuple[str, str] | None = None,
        mapped_field: tuple[str, str, str] | None = None,
        duplicate_class: str | None = None,
    ) -> str:
        """生成真实形态的 mapping，默认不输出完全未改名的 kept 字段。"""
        lines = ["# compiler: R8", "# min-api: 21"]
        for class_name in verifier.EXPECTED_WIRE_FIELDS:
            if class_name == omitted_class:
                continue
            output_class = renamed_class[1] if renamed_class and renamed_class[0] == class_name else class_name
            class_lines = [f"{class_name} -> {output_class}:"]
            if mapped_field and mapped_field[0] == class_name:
                class_lines.append(
                    f"    java.lang.Object {mapped_field[1]} -> {mapped_field[2]}"
                )
            class_lines.append("    1:1:void <init>():0:0 -> <init>")
            lines.extend(class_lines)
            if class_name == duplicate_class:
                lines.extend(class_lines)
        return "\n".join(lines) + "\n"

    def build_seeds(
        self,
        *,
        omitted_class: str | None = None,
        omitted_field: tuple[str, str] | None = None,
        omitted_constructor: str | None = None,
    ) -> str:
        """生成覆盖目标类、字段和无参构造的最小 R8 seeds fixture。"""
        lines: list[str] = []
        for class_name, field_names in verifier.EXPECTED_WIRE_FIELDS.items():
            if class_name != omitted_class:
                lines.append(class_name)
            for field_name in field_names:
                if omitted_field == (class_name, field_name):
                    continue
                lines.append(f"{class_name}: java.lang.Object {field_name}")
            if class_name != omitted_constructor:
                simple_class_name = class_name.rsplit(".", maxsplit=1)[-1]
                lines.append(f"{class_name}: {simple_class_name}()")
        return "\n".join(lines) + "\n"

    def run_verifier(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        """以真实 CLI 入口运行校验器并捕获退出码与输出。"""
        return subprocess.run(
            [sys.executable, str(SCRIPTS_DIR / "verify_paragraph_review_r8.py"), *arguments],
            check=False,
            capture_output=True,
            text=True,
        )

    def write_outputs(
        self,
        directory: str,
        mapping_text: str,
        seeds_text: str | None = None,
    ) -> Path:
        """把合成 mapping 及可选 sibling seeds 写入临时目录。"""
        mapping_path = Path(directory) / "mapping.txt"
        mapping_path.write_text(mapping_text, encoding="utf-8")
        if seeds_text is not None:
            mapping_path.with_name("seeds.txt").write_text(seeds_text, encoding="utf-8")
        return mapping_path

    def test_complete_mapping_passes(self) -> None:
        """无字段行的真实形态 mapping 配合完整 seeds 时返回成功。"""
        with tempfile.TemporaryDirectory() as directory:
            mapping_path = self.write_outputs(
                directory,
                self.build_mapping(),
                self.build_seeds(),
            )
            result = self.run_verifier(str(mapping_path))

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("10 个 wire 类、72 个字段和无参构造", result.stdout)

    def test_no_mapping_argument_fails(self) -> None:
        """未提供任何 mapping 路径时返回非零。"""
        result = self.run_verifier()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("至少需要一个 mapping.txt", result.stderr)

    def test_missing_mapping_file_fails(self) -> None:
        """mapping 文件不存在时返回非零。"""
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_verifier(str(Path(directory) / "missing.txt"))

        self.assertNotEqual(0, result.returncode)
        self.assertIn("mapping 文件不存在", result.stderr)

    def test_missing_seeds_file_fails(self) -> None:
        """同目录缺少 seeds 文件时返回非零。"""
        with tempfile.TemporaryDirectory() as directory:
            mapping_path = self.write_outputs(directory, self.build_mapping())
            result = self.run_verifier(str(mapping_path))

        self.assertNotEqual(0, result.returncode)
        self.assertIn("seeds 文件不存在", result.stderr)

    def test_missing_class_fails(self) -> None:
        """任一目标 wire 类缺失时返回非零。"""
        missing_class = next(iter(verifier.EXPECTED_WIRE_FIELDS))
        with tempfile.TemporaryDirectory() as directory:
            mapping_path = self.write_outputs(
                directory,
                self.build_mapping(omitted_class=missing_class),
                self.build_seeds(),
            )
            result = self.run_verifier(str(mapping_path))

        self.assertNotEqual(0, result.returncode)
        self.assertIn(f"缺少类 {missing_class}", result.stderr)

    def test_missing_seed_field_fails(self) -> None:
        """seeds 中任一目标 wire 字段缺失时返回非零。"""
        class_name = next(iter(verifier.EXPECTED_WIRE_FIELDS))
        field_name = verifier.EXPECTED_WIRE_FIELDS[class_name][0]
        with tempfile.TemporaryDirectory() as directory:
            mapping_path = self.write_outputs(
                directory,
                self.build_mapping(),
                self.build_seeds(omitted_field=(class_name, field_name)),
            )
            result = self.run_verifier(str(mapping_path))

        self.assertNotEqual(0, result.returncode)
        self.assertIn(f"seeds 缺少字段 {class_name}.{field_name}", result.stderr)

    def test_missing_seed_constructor_fails(self) -> None:
        """seeds 中任一目标 wire 无参构造缺失时返回非零。"""
        class_name = next(iter(verifier.EXPECTED_WIRE_FIELDS))
        with tempfile.TemporaryDirectory() as directory:
            mapping_path = self.write_outputs(
                directory,
                self.build_mapping(),
                self.build_seeds(omitted_constructor=class_name),
            )
            result = self.run_verifier(str(mapping_path))

        self.assertNotEqual(0, result.returncode)
        self.assertIn(f"seeds 缺少无参构造 {class_name}()", result.stderr)

    def test_renamed_class_fails(self) -> None:
        """任一目标 wire 类被混淆改名时返回非零。"""
        class_name = next(iter(verifier.EXPECTED_WIRE_FIELDS))
        with tempfile.TemporaryDirectory() as directory:
            mapping_path = self.write_outputs(
                directory,
                self.build_mapping(renamed_class=(class_name, "a.b")),
                self.build_seeds(),
            )
            result = self.run_verifier(str(mapping_path))

        self.assertNotEqual(0, result.returncode)
        self.assertIn(f"类被改名 {class_name} -> a.b", result.stderr)

    def test_renamed_field_fails(self) -> None:
        """任一目标 wire 字段被混淆改名时返回非零。"""
        class_name = next(iter(verifier.EXPECTED_WIRE_FIELDS))
        field_name = verifier.EXPECTED_WIRE_FIELDS[class_name][0]
        with tempfile.TemporaryDirectory() as directory:
            mapping_path = self.write_outputs(
                directory,
                self.build_mapping(mapped_field=(class_name, field_name, "a")),
                self.build_seeds(),
            )
            result = self.run_verifier(str(mapping_path))

        self.assertNotEqual(0, result.returncode)
        self.assertIn(f"字段被改名 {class_name}.{field_name} -> a", result.stderr)

    def test_duplicate_class_section_fails(self) -> None:
        """同一目标类出现重复 mapping 段落时返回非零。"""
        class_name = next(iter(verifier.EXPECTED_WIRE_FIELDS))
        with tempfile.TemporaryDirectory() as directory:
            mapping_path = self.write_outputs(
                directory,
                self.build_mapping(duplicate_class=class_name),
                self.build_seeds(),
            )
            result = self.run_verifier(str(mapping_path))

        self.assertNotEqual(0, result.returncode)
        self.assertIn(f"重复声明类 {class_name}", result.stderr)


if __name__ == "__main__":
    unittest.main()
