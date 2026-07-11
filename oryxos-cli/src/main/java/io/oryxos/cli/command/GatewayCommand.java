package io.oryxos.cli.command;

import io.oryxos.cli.OryxOsRuntime;
import org.springframework.boot.SpringApplication;
import picocli.CommandLine.Command;

/** 重命令：守护进程骨架——起完整运行时常驻；多 Channel 挂载属扩展阶段。 */
@Command(
    name = "gateway",
    description = "守护进程模式（多 Channel 挂载属扩展阶段）",
    mixinStandardHelpOptions = true)
public class GatewayCommand implements Runnable {

  @Override
  public void run() {
    System.out.println("OryxOS 运行时已启动（gateway 骨架；IM Channel 挂载属扩展阶段）。Ctrl-C 退出。");
    SpringApplication.run(OryxOsRuntime.class, new String[0]);
    ServeCommand.keepAlive();
  }
}
