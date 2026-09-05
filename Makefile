# OryxOS 构建 / 发行版打包
#
#   make build           编译并打包可执行 jar（含管理台前端）
#   make release         打发行版 dist/oryxos-<version>.tar.gz（bin/ config/ libs/）
#   make docker          用已构建的胖 jar 构建本地镜像 oryxos:<version>（不跑 Maven，镜像内只有 JRE）
#   make sync-upstream   把 oryx-labs/main 合并进当前分支（避免 OWASP 抑制落后）
#   make hooks           安装 .githooks（push 前检查 OWASP/pom 是否落后 main）
#   make clean           清理 dist/ 与 maven target/
#   make help            帮助
#
# 发行版结构（解压后）：
#   oryxos-<version>/
#   ├── bin/oryx-server                     启停脚本：start | stop | restart | status
#   ├── config/application.yml.example      配置模板（首次 start 自动生成 application.yml）
#   └── libs/oryxos-boot-<version>.jar      含管理台前端的可执行胖 jar

SHELL := /bin/bash

# 项目版本：取 <artifactId>oryxos</artifactId> 之后的 <version>（跳过 spring-boot 父版本）
VERSION   := $(shell awk '/<artifactId>oryxos<\/artifactId>/{f=1} f&&/<version>/{gsub(/.*<version>|<\/version>.*/,"");gsub(/[[:space:]]/,"");print;exit}' pom.xml)
DIST_NAME := oryxos-$(VERSION)
DIST_DIR  := dist
STAGE     := $(DIST_DIR)/$(DIST_NAME)
BOOT_JAR  := oryxos-boot/target/oryxos-boot-$(VERSION).jar
TARBALL   := $(DIST_DIR)/$(DIST_NAME).tar.gz

.PHONY: help build release docker clean sync-upstream hooks

help:
	@echo "OryxOS make 目标（version = $(VERSION)）："
	@echo "  make build          编译打包可执行 jar（含管理台前端）"
	@echo "  make release        打发行版 $(TARBALL)（bin/ config/ libs/）"
	@echo "  make docker         构建本地镜像 oryxos:$(VERSION)（依赖 build 产出的胖 jar）"
	@echo "  make sync-upstream  合并 oryx-labs/main 到当前分支"
	@echo "  make hooks          安装 push 前的 OWASP/pom 落后检查"
	@echo "  make clean          清理 dist/ 与 maven target/"

sync-upstream:
	@bash scripts/sync-upstream.sh

hooks:
	@bash scripts/install-git-hooks.sh

# 全量打包：frontend-maven-plugin 会一并构建管理台前端进 jar（不加 -Dfrontend.skip）
build:
	mvn clean package -DskipTests

# 依赖 build 产出的胖 jar，组装成 bin/config/libs 三目录并压成 tar.gz
release: build
	@echo "==> 组装发行版 $(DIST_NAME)"
	@test -f "$(BOOT_JAR)" || { echo "[ERROR] 找不到 $(BOOT_JAR)，构建可能失败"; exit 1; }
	rm -rf "$(STAGE)"
	mkdir -p "$(STAGE)/bin" "$(STAGE)/config" "$(STAGE)/libs"
	cp bin/oryx-server "$(STAGE)/bin/oryx-server"
	chmod +x "$(STAGE)/bin/oryx-server"
	cp config/application.yml.example "$(STAGE)/config/application.yml.example"
	cp "$(BOOT_JAR)" "$(STAGE)/libs/"
	cp README.md LICENSE "$(STAGE)/" 2>/dev/null || true
	tar -C "$(DIST_DIR)" -czf "$(TARBALL)" "$(DIST_NAME)"
	@echo "==> 完成：$(TARBALL)  ($$(du -sh "$(TARBALL)" | cut -f1))"
	@echo "==> 内容："
	@tar -tzf "$(TARBALL)"

# 依赖 build 产出的胖 jar 构建本地镜像（镜像内不跑 Maven——jar 平台无关，多架构只需换基础镜像层）。
# JAR_FILE 显式钉死版本：target/ 里若残留多个旧版本 jar，Dockerfile 的默认 glob 会有歧义，这里不猜。
docker:
	@test -f "$(BOOT_JAR)" || { echo "[ERROR] 找不到 $(BOOT_JAR)，先 make build"; exit 1; }
	docker build --build-arg JAR_FILE=$(BOOT_JAR) -t oryxos:$(VERSION) .

clean:
	rm -rf "$(DIST_DIR)"
	-mvn clean -q
