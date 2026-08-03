#!/usr/bin/env python3
"""校验 release R8 产物中段评 Gson wire DTO 未被裁剪或改名。"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence


WIRE_PACKAGE = "io.legado.app.model.review.wire"
EXPECTED_WIRE_FIELDS: dict[str, tuple[str, ...]] = {
    f"{WIRE_PACKAGE}.ReviewEnvelopeWire": (
        "contract", "code", "message", "data", "error",
    ),
    f"{WIRE_PACKAGE}.ReviewErrorWire": (
        "type", "retryable", "parameter",
    ),
    f"{WIRE_PACKAGE}.ReviewWarningWire": (
        "scope", "type", "retryable", "paraId", "commentId",
    ),
    f"{WIRE_PACKAGE}.ReviewIndexWire": (
        "itemId", "bookId", "itemVersion", "paragraphs", "partial", "warnings",
    ),
    f"{WIRE_PACKAGE}.ReviewParagraphWire": (
        "paraId", "count", "hot", "userCount", "detailLoaded", "comments",
    ),
    f"{WIRE_PACKAGE}.ParagraphCommentPageWire": (
        "itemId", "bookId", "itemVersion", "paraId", "comments", "total",
        "hasMore", "nextCursor",
    ),
    f"{WIRE_PACKAGE}.ParagraphCommentWire": (
        "commentId", "text", "images", "userId", "userName", "userAvatar",
        "createTimestamp", "diggCount", "replyCount", "repliesLoaded", "replies",
        "replyTotal", "replyHasMore", "replyNextCursor",
    ),
    f"{WIRE_PACKAGE}.ParagraphCommentImageWire": (
        "url", "width", "height", "format",
    ),
    f"{WIRE_PACKAGE}.ParagraphReplyPageWire": (
        "itemId", "bookId", "commentId", "replies", "total", "hasMore", "nextCursor",
    ),
    f"{WIRE_PACKAGE}.ParagraphReplyWire": (
        "replyId", "parentReplyId", "replyToCommentId", "replyToReplyId", "text",
        "images", "userId", "userName", "userAvatar", "replyToUserName",
        "createTimestamp", "diggCount", "replyCount", "children",
    ),
}

CLASS_MAPPING_PATTERN = re.compile(r"^(?P<original>\S+) -> (?P<renamed>\S+):$")
FIELD_MAPPING_PATTERN = re.compile(
    r"^\s+(?P<type>\S+)\s+(?P<original>[^\s():]+)\s+->\s+(?P<renamed>\S+)$"
)


@dataclass
class WireClassMapping:
    """保存单个目标 wire 类在 R8 mapping 中的名称和字段映射。"""

    renamed: str
    fields: dict[str, str] = field(default_factory=dict)


@dataclass
class WireClassSeeds:
    """保存单个目标 wire 类在 R8 seeds 中的保留证据。"""

    class_kept: bool = False
    fields: set[str] = field(default_factory=set)
    no_arg_constructor_kept: bool = False


def parse_target_mappings(mapping_text: str) -> tuple[dict[str, WireClassMapping], list[str]]:
    """解析 mapping 文本中的目标 wire 类，并收集重复段落或字段错误。"""
    mappings: dict[str, WireClassMapping] = {}
    errors: list[str] = []
    current_class: str | None = None

    for line_number, line in enumerate(mapping_text.splitlines(), start=1):
        class_match = CLASS_MAPPING_PATTERN.match(line)
        if class_match:
            original = class_match.group("original")
            current_class = original if original in EXPECTED_WIRE_FIELDS else None
            if current_class is None:
                continue
            if current_class in mappings:
                errors.append(f"第 {line_number} 行重复声明类 {current_class}")
                current_class = None
                continue
            mappings[current_class] = WireClassMapping(class_match.group("renamed"))
            continue

        if current_class is None:
            continue
        field_match = FIELD_MAPPING_PATTERN.match(line)
        if field_match is None:
            continue
        original_field = field_match.group("original")
        if original_field not in EXPECTED_WIRE_FIELDS[current_class]:
            continue
        class_fields = mappings[current_class].fields
        if original_field in class_fields:
            errors.append(f"第 {line_number} 行重复声明字段 {current_class}.{original_field}")
            continue
        class_fields[original_field] = field_match.group("renamed")

    return mappings, errors


def validate_mapping_text(mapping_text: str) -> list[str]:
    """验证 mapping 包含全部原名类，且已输出的目标字段没有改名。"""
    mappings, errors = parse_target_mappings(mapping_text)
    for class_name in EXPECTED_WIRE_FIELDS:
        class_mapping = mappings.get(class_name)
        if class_mapping is None:
            errors.append(f"缺少类 {class_name}")
            continue
        if class_mapping.renamed != class_name:
            errors.append(
                f"类被改名 {class_name} -> {class_mapping.renamed}"
            )
        for field_name, renamed_field in class_mapping.fields.items():
            if renamed_field != field_name:
                errors.append(
                    f"字段被改名 {class_name}.{field_name} -> {renamed_field}"
                )
    return errors


def parse_target_seeds(seeds_text: str) -> dict[str, WireClassSeeds]:
    """解析 seeds 文本中的目标类、字段和无参构造保留证据。"""
    seeds = {class_name: WireClassSeeds() for class_name in EXPECTED_WIRE_FIELDS}
    for line in seeds_text.splitlines():
        if line in seeds:
            seeds[line].class_kept = True
            continue
        class_name, separator, member = line.partition(": ")
        if not separator or class_name not in seeds:
            continue
        simple_class_name = class_name.rsplit(".", maxsplit=1)[-1]
        if member == f"{simple_class_name}()":
            seeds[class_name].no_arg_constructor_kept = True
            continue
        if "(" in member:
            continue
        field_name = member.rsplit(" ", maxsplit=1)[-1]
        if field_name in EXPECTED_WIRE_FIELDS[class_name]:
            seeds[class_name].fields.add(field_name)
    return seeds


def validate_seeds_text(seeds_text: str) -> list[str]:
    """验证 seeds 为全部目标类、字段和无参构造提供 keep 证据。"""
    seeds = parse_target_seeds(seeds_text)
    errors: list[str] = []
    for class_name, expected_fields in EXPECTED_WIRE_FIELDS.items():
        class_seeds = seeds[class_name]
        if not class_seeds.class_kept:
            errors.append(f"seeds 缺少类 {class_name}")
        if not class_seeds.no_arg_constructor_kept:
            errors.append(f"seeds 缺少无参构造 {class_name}()")
        for field_name in expected_fields:
            if field_name not in class_seeds.fields:
                errors.append(f"seeds 缺少字段 {class_name}.{field_name}")
    return errors


def read_required_text(path: Path, artifact_name: str) -> tuple[str | None, list[str]]:
    """读取必需的 R8 文本产物，并返回脱敏且稳定的读取错误。"""
    if not path.is_file():
        return None, [f"{artifact_name} 文件不存在或不是普通文件: {path}"]
    try:
        artifact_text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        return None, [f"无法读取 {artifact_name} 文件 {path}: {exc.__class__.__name__}"]
    if not artifact_text.strip():
        return None, [f"{artifact_name} 文件为空: {path}"]
    return artifact_text, []


def validate_release_outputs(mapping_path: Path) -> list[str]:
    """联合验证指定 mapping 及同目录 seeds 形成完整 shrink 保留证据。"""
    mapping_text, errors = read_required_text(mapping_path, "mapping")
    if mapping_text is not None:
        errors.extend(validate_mapping_text(mapping_text))

    seeds_path = mapping_path.with_name("seeds.txt")
    seeds_text, seeds_errors = read_required_text(seeds_path, "seeds")
    errors.extend(seeds_errors)
    if seeds_text is not None:
        errors.extend(validate_seeds_text(seeds_text))
    return errors


def build_argument_parser() -> argparse.ArgumentParser:
    """构建支持一个或多个真实 R8 mapping 路径的命令行解析器。"""
    parser = argparse.ArgumentParser(
        description="联合校验 release R8 mapping 和同目录 seeds 中的段评 wire DTO。"
    )
    parser.add_argument(
        "mapping",
        nargs="*",
        type=Path,
        help="待校验的 app release mapping.txt，可同时传入多个变体",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """执行 mapping 校验，并以非零状态阻止不安全的 release 产物。"""
    parser = build_argument_parser()
    args = parser.parse_args(argv)
    if not args.mapping:
        parser.print_usage(sys.stderr)
        print("错误: 至少需要一个 mapping.txt", file=sys.stderr)
        return 2

    failed = False
    expected_field_count = sum(len(fields) for fields in EXPECTED_WIRE_FIELDS.values())
    for mapping_path in args.mapping:
        errors = validate_release_outputs(mapping_path)
        if errors:
            failed = True
            for error in errors:
                print(f"错误 [{mapping_path}]: {error}", file=sys.stderr)
            continue
        print(
            f"通过 [{mapping_path}]: "
            f"mapping/seeds 保留 {len(EXPECTED_WIRE_FIELDS)} 个 wire 类、"
            f"{expected_field_count} 个字段和无参构造"
        )
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
