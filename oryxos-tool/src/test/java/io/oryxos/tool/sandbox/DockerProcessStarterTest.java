package io.oryxos.tool.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ToolExecutionContext;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 024 T009：DockerProcessStarter 单元层——argv 构造委托（DockerRunSpec 已单独钉死）、CLI 启动失败的
 * 分类报错与临时文件清理、审计上下文置入（backend=docker + 惰性容器 ID）。真机行为见契约测试 IT。
 */
class DockerProcessStarterTest {

  @TempDir java.nio.file.Path tempDir;

  @AfterEach
  void cleanupContext() {
    ToolExecutionContext.clear();
  }

  private DockerProcessStarter starter(ProcessStarter cliStarter) {
    return new DockerProcessStarter(
        new ExecutionBackendProperties("docker", "alpine:3.20", null, null, null, null),
        new WorkspacePathMapper(tempDir),
        containerId -> {},
        cliStarter);
  }

  @Test
  @DisplayName("CLI启动失败_分类报错含排障提示与配置键_临时文件清理")
  void cliStartFailureFailsLoudWithHint() throws IOException {
    java.nio.file.Path[] leaked = new java.nio.file.Path[1];
    ProcessStarter failingCli =
        command -> {
          // 从 argv 里找回 --cidfile 的路径，验证失败路径上会被清理
          int idx = command.indexOf("--cidfile");
          leaked[0] = java.nio.file.Path.of(command.get(idx + 1));
          throw new IOException("Cannot run program \"docker\"");
        };

    IOException e = assertThrows(IOException.class, () -> starter(failingCli).start(List.of("ls")));

    assertTrue(e.getMessage().contains("docker CLI 启动失败"));
    assertTrue(e.getMessage().contains("oryxos.sandbox.execution.backend=docker"));
    assertTrue(e.getMessage().contains("PATH"));
    assertTrue(!java.nio.file.Files.exists(leaked[0]), "失败路径上 cid 临时目录应被清理");
    assertEquals(null, ToolExecutionContext.executionBackend(), "失败不置入审计上下文");
  }

  @Test
  @DisplayName("启动成功_返回Wrapper_审计上下文置docker_容器ID惰性可读")
  void successWrapsAndSetsContext() throws Exception {
    FakeCliProcess cli = new FakeCliProcess();
    DockerProcessStarter starter = starter(command -> cli);

    Process wrapper = starter.start(List.of("echo", "hi"));

    assertInstanceOf(CidfileProcessWrapper.class, wrapper);
    assertEquals("docker", ToolExecutionContext.executionBackend());
    // 惰性读取：cidfile 未写时 null；写入后可读（审计时点语义）
    assertEquals(null, ToolExecutionContext.containerId());
  }

  @Test
  @DisplayName("argv委托DockerRunSpec_含docker_run与镜像")
  void argvDelegatesToRunSpec() throws Exception {
    AtomicReference<List<String>> captured = new AtomicReference<>();
    DockerProcessStarter starter =
        starter(
            command -> {
              captured.set(command);
              return new FakeCliProcess();
            });

    starter.start(List.of("cat", "/etc/os-release"));

    List<String> argv = captured.get();
    assertEquals("docker", argv.get(0));
    assertEquals("run", argv.get(1));
    assertTrue(argv.contains("alpine:3.20"));
    assertTrue(argv.contains("--cidfile"));
  }

  /** 最小假 CLI 进程（无 IO 语义，只验证包装与上下文）。 */
  static final class FakeCliProcess extends Process {
    @Override
    public java.io.OutputStream getOutputStream() {
      return java.io.OutputStream.nullOutputStream();
    }

    @Override
    public java.io.InputStream getInputStream() {
      return java.io.InputStream.nullInputStream();
    }

    @Override
    public java.io.InputStream getErrorStream() {
      return java.io.InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {}
  }
}
