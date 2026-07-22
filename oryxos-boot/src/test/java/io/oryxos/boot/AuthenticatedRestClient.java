package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Test-side HTTP client that exercises the real admin login + CSRF flow. */
final class AuthenticatedRestClient {

  private static final String USERNAME = "admin";
  private static final String PASSWORD = "secret";

  private final TestRestTemplate rest;
  private final ObjectMapper mapper;

  private HttpHeaders authenticatedHeaders;

  AuthenticatedRestClient(TestRestTemplate rest, ObjectMapper mapper) {
    this.rest = rest;
    this.mapper = mapper;
  }

  JsonNode getData(String path) throws Exception {
    return dataOf(rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers()), String.class));
  }

  JsonNode postJson(String path, String json) throws Exception {
    return exchangeWithJson(HttpMethod.POST, path, json);
  }

  JsonNode postEmpty(String path) throws Exception {
    return exchangeWithJson(HttpMethod.POST, path, "");
  }

  JsonNode putJson(String path, String json) throws Exception {
    return exchangeWithJson(HttpMethod.PUT, path, json);
  }

  private JsonNode exchangeWithJson(HttpMethod method, String path, String json) throws Exception {
    HttpHeaders requestHeaders = headers();
    requestHeaders.setContentType(MediaType.APPLICATION_JSON);
    return dataOf(
        rest.exchange(path, method, new HttpEntity<>(json, requestHeaders), String.class));
  }

  private HttpHeaders headers() throws Exception {
    HttpHeaders copy = new HttpHeaders();
    copy.addAll(loginHeaders());
    return copy;
  }

  private HttpHeaders loginHeaders() throws Exception {
    if (authenticatedHeaders != null) {
      return authenticatedHeaders;
    }

    ResponseEntity<String> csrfResponse = rest.getForEntity("/api/v1/auth/csrf", String.class);
    JsonNode csrf = dataOf(csrfResponse);
    String csrfHeaderName = csrf.get("headerName").asText();
    String csrfToken = csrf.get("token").asText();
    String csrfCookies = cookieHeader(csrfResponse);

    HttpHeaders loginHeaders = new HttpHeaders();
    loginHeaders.setContentType(MediaType.APPLICATION_JSON);
    loginHeaders.add(csrfHeaderName, csrfToken);
    loginHeaders.add(HttpHeaders.COOKIE, csrfCookies);
    ResponseEntity<String> loginResponse =
        rest.postForEntity(
            "/api/v1/auth/login",
            new HttpEntity<>(
                "{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}",
                loginHeaders),
            String.class);
    dataOf(loginResponse);

    authenticatedHeaders = new HttpHeaders();
    authenticatedHeaders.add(csrfHeaderName, csrfToken);
    authenticatedHeaders.add(
        HttpHeaders.COOKIE, mergeCookies(csrfCookies, cookieHeader(loginResponse)));
    return authenticatedHeaders;
  }

  private JsonNode dataOf(ResponseEntity<String> resp) throws Exception {
    assertEquals(200, resp.getStatusCode().value(), "HTTP should be 200");
    JsonNode body = mapper.readTree(resp.getBody());
    assertEquals(0, body.get("code").asInt(), "API envelope code should be 0");
    return body.get("data");
  }

  private static String cookieHeader(ResponseEntity<String> response) {
    return mergeCookies(
        "", String.join("; ", response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)));
  }

  private static String mergeCookies(String first, String second) {
    Map<String, String> cookies = new LinkedHashMap<>();
    addCookies(cookies, first);
    addCookies(cookies, second);
    return String.join("; ", cookies.values());
  }

  private static void addCookies(Map<String, String> cookies, String cookieHeader) {
    if (cookieHeader == null || cookieHeader.isBlank()) {
      return;
    }
    String[] parts = cookieHeader.split("; ");
    for (String part : parts) {
      int equals = part.indexOf('=');
      if (equals < 1) {
        continue;
      }
      String name = part.substring(0, equals);
      if (isCookieAttribute(name)) {
        continue;
      }
      cookies.put(name, part);
    }
  }

  private static boolean isCookieAttribute(String name) {
    return "Path".equalsIgnoreCase(name)
        || "Max-Age".equalsIgnoreCase(name)
        || "Expires".equalsIgnoreCase(name)
        || "SameSite".equalsIgnoreCase(name);
  }
}
