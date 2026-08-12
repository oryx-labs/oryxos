package io.oryxos.cli.command;

import io.oryxos.cli.OryxOsRuntime;
import org.springframework.boot.SpringApplication;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** 重命令：启动骨架——起完整运行时常驻；REST 端点已在 Web 层交付。 */
@Command(
    name = "serve",
    description = "启动 HTTP API 服务（REST 端点 + Web 管理台）",
    mixinStandardHelpOptions = true)
public class ServeCommand implements Runnable {

  @Option(names = "--port", defaultValue = "8080", description = "监听端口")
  int port;

  @Override
  public void run() {
    System.setProperty("server.port", String.valueOf(port));
    System.out.println("OryxOS 运行已启动（REST API: /api/v1, Web 管理台: /admin/）。Ctrl-C 退出。");
    SpringApplication.run(OryxOsRuntime.class, new String[0]);
    keepAlive();
  }

  static void keepAlive() {
    try {
      Thread.currentThread().join(); // 常驻直到进程被终止
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
