package io.oryxos.web.config;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 管理台（Vue SPA）静态托管：{@code /admin/**} 映射到打进 jar 的 {@code classpath:/static/admin/}， 未命中的前端路径回落
 * {@code index.html}（SPA 前端路由，刷新子路由不 404）。{@code /api/v1/**} 由 Controller 处理，不受影响。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private static final String INDEX = "index.html";
  private static final String SLASH = "/";
  // 带内容 hash 的静态资源（assets/index-<hash>.js）可长期强缓存——内容变则文件名变
  private static final long STATIC_CACHE_DAYS = 365;

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    // /admin（无尾斜杠）跳到 /admin/，让 index.html 的 base '/admin/' 资源路径正确解析
    registry.addRedirectViewController("/admin", "/admin/");
    // /admin/（入口）转发到 index.html——ResourceHttpRequestHandler 对空路径不走 resolver，必须显式转发
    registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    // hash 命名的资源：文件名即指纹，可放心 immutable 长缓存（比 /admin/** 更具体，优先命中）
    registry
        .addResourceHandler("/admin/assets/**")
        .addResourceLocations("classpath:/static/admin/assets/")
        .setCacheControl(
            CacheControl.maxAge(STATIC_CACHE_DAYS, TimeUnit.DAYS).cachePublic().immutable());
    // index.html + SPA 回落：文件名无 hash，必须 no-cache 强制每次向服务端校验，
    // 否则重建后浏览器会用旧壳指向已删除的旧 bundle（表现为“只有某个页签能用”）
    registry
        .addResourceHandler("/admin/**")
        .addResourceLocations("classpath:/static/admin/")
        .setCacheControl(CacheControl.noCache())
        .resourceChain(true)
        .addResolver(
            new PathResourceResolver() {
              @Override
              protected Resource getResource(String resourcePath, Resource location)
                  throws IOException {
                // 空路径（/admin/ 入口）或目录一律回落 index.html——否则会把目录当资源返，404
                if (resourcePath.isEmpty() || resourcePath.endsWith(SLASH)) {
                  return location.createRelative(INDEX);
                }
                Resource requested = location.createRelative(resourcePath);
                // 命中真实静态文件就返它；否则回落 index.html（SPA 路由兜底）
                return requested.exists() && requested.isReadable()
                    ? requested
                    : location.createRelative(INDEX);
              }
            });
  }
}
