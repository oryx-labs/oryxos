package io.oryxos.web.controller.dto;

/**
 * POST /skills/import 请求体：从 GitHub 拉取 Skill（第 32 节）。{@code url} 必须是 GitHub 目录 URL，形如 {@code
 * https://github.com/<owner>/<repo>/tree/<branch>/<path>}——后端递归拉下该目录下全部文件建库，不是抓网页正文。
 *
 * <p>{@code name} 可空——缺省用 SKILL.md frontmatter 的 name，再缺省用目录末段推断名；填了则以它为准。
 */
public record ImportSkillRequest(String url, String name) {}
