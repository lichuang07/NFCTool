#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把本仓库推送到 GitHub 并发布 APK Release。

## 为什么不用 git push
本机网络只有 api.github.com / uploads.github.com 可达，
github.com 的 443 与 22 端口都被代理拦掉（CONNECT 502），
git push 与 ssh 都走不通。所以这里直接调 GitHub REST API：
代码走 Git Data API（blobs / trees / commits / refs），
APK 走 uploads.github.com 的 Release asset 接口。

## 用法
    python tools/publish_github.py --token ghp_xxxx
    python tools/publish_github.py --token ghp_xxxx --private
    python tools/publish_github.py --token ghp_xxxx --skip-code   # 只发 Release

token 需要的权限（classic）：repo
fine-grained：Contents 读写 + Releases 读写 + Administration 读写（建仓需要）
"""
import argparse
import base64
import io
import json
import mimetypes
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.github.com"
UPLOAD = "https://uploads.github.com"
# 单棵 tree 一次最多塞多少条目。分批发，避免请求体过大被拒。
TREE_BATCH = 80
# 上传/建仓偶发限流，简单重试
MAX_RETRY = 3


def api(method, path, token, body=None, raw=False):
    url = API + path
    data = None
    headers = {
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "NFCTool-publish-script",
    }
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    last_err = None
    for attempt in range(MAX_RETRY):
        try:
            req = urllib.request.Request(url, data=data, headers=headers, method=method)
            with urllib.request.urlopen(req, timeout=60) as resp:
                payload = resp.read()
                if raw:
                    return payload
                return json.loads(payload.decode("utf-8")) if payload else {}
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "ignore")[:400]
            last_err = "%s %s -> %s %s" % (method, path, e.code, detail)
            if e.code in (403, 429) or e.code >= 500:
                time.sleep(2 * (attempt + 1))
                continue
            raise SystemExit("GitHub API 失败: " + last_err)
        except urllib.error.URLError as e:
            last_err = "%s %s -> %s" % (method, path, e.reason)
            time.sleep(2 * (attempt + 1))
    raise SystemExit("GitHub API 重试后仍失败: " + last_err)


def ensure_repo(token, name, private, description):
    """仓库不存在就建；已存在直接复用（方便重复运行）。"""
    try:
        req = urllib.request.Request(
            API + "/repos/" + urllib.parse.quote(token_owner(token)) + "/" + name,
            headers={"Authorization": "Bearer " + token,
                     "Accept": "application/vnd.github+json",
                     "User-Agent": "NFCTool-publish-script"},
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise SystemExit("查询仓库失败: %s" % e.read().decode("utf-8", "ignore")[:300])
    print(">>> 创建仓库 %s (private=%s)" % (name, private))
    return api("POST", "/user/repos", token, {
        "name": name,
        "description": description,
        "private": private,
        "auto_init": False,
    })


def token_owner(token):
    me = api("GET", "/user", token)
    return me["login"]


def tracked_files():
    """从 git 索引拿文件清单：路径 + mode（保留可执行位）。"""
    # ⚠️ core.quotepath 必须关：默认会把中文文件名转义成 "NFC\345\212\237...md"
    out = subprocess.check_output(
        ["git", "-c", "core.quotepath=false", "ls-files", "-s"], cwd=ROOT
    ).decode("utf-8", "ignore")
    files = []
    for line in out.splitlines():
        if not line.strip():
            continue
        meta, path = line.split("\t", 1)
        mode = meta.split()[0]
        files.append((path.replace("\\", "/"), mode))
    return files


def bootstrap_repo(token, owner, repo, branch):
    """
    完全空的仓库用 Git Data API 建 blob 会报 409 "Git Repository is empty"。
    先用 contents API 提交 README 造出第一个 commit，之后 tree / commit 就能正常工作。
    README 的内容与最终入库的一致，后续 tree 会用同样的 blob 覆盖，不会留下痕迹。
    """
    try:
        api("GET", "/repos/%s/%s/git/ref/heads/%s" % (owner, repo, branch), token)
        return False  # 仓库非空，不需要引导
    except SystemExit:
        pass
    print(">>> 空仓库，先用 README 造第一个 commit")
    with io.open(os.path.join(ROOT, "README.md"), "rb") as f:
        content = base64.b64encode(f.read()).decode("ascii")
    api("PUT", "/repos/%s/%s/contents/README.md" % (owner, repo), token, {
        "message": "chore: bootstrap repository",
        "content": content,
        "branch": branch,
    })
    return True


def push_code(token, owner, repo, branch):
    bootstrap_repo(token, owner, repo, branch)
    print(">>> 读取工作区文件")
    files = tracked_files()
    print("    共 %d 个文件" % len(files))

    # 1) 逐个创建 blob（二进制走 base64）
    entries = []
    for idx, (path, mode) in enumerate(files, 1):
        full = os.path.join(ROOT, path)
        with io.open(full, "rb") as f:
            raw = f.read()
        try:
            text = raw.decode("utf-8")
            encoding = "utf-8"
            content = text
        except UnicodeDecodeError:
            encoding = "base64"
            content = base64.b64encode(raw).decode("ascii")
        blob = api("POST", "/repos/%s/%s/git/blobs" % (owner, repo), token,
                   {"content": content, "encoding": encoding})
        entries.append({
            "path": path,
            "mode": mode,
            "type": "blob",
            "sha": blob["sha"],
        })
        if idx % 20 == 0 or idx == len(files):
            print("    blobs %d/%d" % (idx, len(files)))

    # 2) 分批建 tree（每批以前一批的 tree 为 base 累积）
    print(">>> 创建 tree（%d 批）" % ((len(entries) + TREE_BATCH - 1) // TREE_BATCH))
    tree_sha = None
    for i in range(0, len(entries), TREE_BATCH):
        batch = entries[i:i + TREE_BATCH]
        body = {"tree": batch}
        if tree_sha:
            body["base_tree"] = tree_sha
        tree = api("POST", "/repos/%s/%s/git/trees" % (owner, repo), token, body)
        tree_sha = tree["sha"]
        print("    tree %d -> %s" % (i // TREE_BATCH + 1, tree_sha[:8]))

    # 3) commit
    parent = None
    try:
        ref = api("GET", "/repos/%s/%s/git/ref/heads/%s" % (owner, repo, branch), token)
        parent = ref["object"]["sha"]
    except SystemExit:
        parent = None
    body = {
        "message": os.environ.get(
            "COMMIT_MSG",
            "Initial commit: NFCTool Android NFC 工具箱",
        ),
        "tree": tree_sha,
    }
    if parent:
        body["parents"] = [parent]
    commit = api("POST", "/repos/%s/%s/git/commits" % (owner, repo), token, body)
    print(">>> commit %s" % commit["sha"][:8])

    # 4) 指向分支（空仓库时 PATCH 会 404，改用 POST 建 ref）
    try:
        api("PATCH", "/repos/%s/%s/git/refs/heads/%s" % (owner, repo, branch), token,
            {"sha": commit["sha"]})
    except SystemExit:
        api("POST", "/repos/%s/%s/git/refs" % (owner, repo), token,
            {"ref": "refs/heads/" + branch, "sha": commit["sha"]})
    print(">>> %s 已指向 %s" % (branch, commit["sha"][:8]))


def read_version():
    src = io.open(os.path.join(ROOT, "app/build.gradle.kts"), encoding="utf-8").read()
    m = re.search(r'versionName\s*=\s*"([^"]+)"', src)
    return m.group(1) if m else "1.0.0"


def asset_name(src_name, tag):
    """
    GitHub 会清洗 Release asset 的文件名：非 ASCII 字符被删除、连续非法字符折叠成一个 '.'。
    实测 `NFC工具箱-5.0MB-主推.apk` 传上去变成 `NFC.-5.0MB-.apk`，已无法辨认。
    所以这里主动改成纯 ASCII 的名字。
    """
    lower = src_name.lower()
    if "对照" in src_name or "noshrink" in lower:
        return "NFCTool-%s-noshrink-compare.apk" % tag
    return "NFCTool-%s-arm64.apk" % tag


def upload_asset(token, owner, repo, release_id, path, name=None):
    name = name or os.path.basename(path)
    ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
    if name.endswith(".apk"):
        ctype = "application/vnd.android.package-archive"
    with io.open(path, "rb") as f:
        data = f.read()
    url = "%s/repos/%s/%s/releases/%d/assets?name=%s" % (
        UPLOAD, owner, repo, release_id, urllib.parse.quote(name),
    )
    req = urllib.request.Request(url, data=data, method="POST", headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "Content-Type": ctype,
        "User-Agent": "NFCTool-publish-script",
    })
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.loads(resp.read().decode("utf-8"))


def publish_release(token, owner, repo, tag):
    body = io.open(os.path.join(ROOT, "docs/RELEASE_NOTES.md"), encoding="utf-8").read() \
        if os.path.exists(os.path.join(ROOT, "docs/RELEASE_NOTES.md")) else \
        "NFCTool %s" % tag
    try:
        rel = api("GET", "/repos/%s/%s/releases/tags/%s" % (owner, repo, tag), token)
        print(">>> Release %s 已存在，复用 (id=%s)" % (tag, rel["id"]))
        # 正文（docs/RELEASE_NOTES.md）可能改过，同步一次
        rel = api("PATCH", "/repos/%s/%s/releases/%d" % (owner, repo, rel["id"]), token,
                  {"body": body})
    except SystemExit:
        rel = api("POST", "/repos/%s/%s/releases" % (owner, repo), token, {
            "tag_name": tag,
            "name": "NFCTool %s" % tag,
            "body": body,
            "draft": False,
            "prerelease": False,
        })
        print(">>> Release %s 已创建 (id=%s)" % (tag, rel["id"]))

    dist = os.path.join(ROOT, "dist")
    existing = {a["name"]: a["id"] for a in rel.get("assets", [])}
    for name in sorted(os.listdir(dist)):
        if not name.endswith(".apk"):
            continue
        path = os.path.join(dist, name)
        target = asset_name(name, tag)
        # 同名 asset 已存在会报 422 already_exists，先删再传
        if target in existing:
            print(">>> 删除同名旧附件 %s" % target)
            api("DELETE", "/repos/%s/%s/releases/assets/%d" % (owner, repo, existing[target]), token)
        size = os.path.getsize(path)
        print(">>> 上传 %s -> %s (%.1f MB)" % (name, target, size / 1024.0 / 1024.0))
        asset = upload_asset(token, owner, repo, rel["id"], path, target)
        print("    -> %s" % asset.get("browser_download_url", asset.get("url")))
    return rel


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--token", default=os.environ.get("GITHUB_TOKEN", ""))
    ap.add_argument("--repo", default="NFCTool")
    ap.add_argument("--branch", default="main")
    ap.add_argument("--private", action="store_true", help="建为私有仓库（默认公开）")
    ap.add_argument("--skip-code", action="store_true", help="跳过推代码，只发 Release")
    ap.add_argument("--skip-release", action="store_true", help="跳过 Release")
    args = ap.parse_args()
    if not args.token:
        raise SystemExit("缺少 token：--token ghp_xxx 或设环境变量 GITHUB_TOKEN")

    global ROOT
    ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    owner = token_owner(args.token)
    print(">>> 身份: %s" % owner)

    repo = ensure_repo(args.token, args.repo, args.private,
                       "Android NFC 标签读写工具箱 —— 读取 / 写入 / 复制 / 整卡备份 / 卡模拟，Compose 实现")
    print(">>> 仓库: %s" % repo["html_url"])

    if not args.skip_code:
        push_code(args.token, owner, args.repo, args.branch)

    if not args.skip_release:
        tag = "v" + read_version()
        rel = publish_release(args.token, owner, repo_name(args.repo), tag)
        print(">>> Release: %s" % rel.get("html_url"))

    print("\n完成。")


def repo_name(name):
    return name


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

if __name__ == "__main__":
    main()
