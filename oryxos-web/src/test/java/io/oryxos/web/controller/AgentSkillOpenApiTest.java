package io.oryxos.web.controller;

import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.skill.SkillManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Locks the generated SpringDoc contract to the multipart and three-state REST API. */
@SpringBootTest(classes = AgentSkillOpenApiTest.TestApplication.class)
@AutoConfigureMockMvc
class AgentSkillOpenApiTest {

  @Autowired private MockMvc mvc;

  @Test
  void generatedDocumentRequiresOneBinaryFilePartAndPublishesErrorEnvelopes() throws Exception {
    String operation = "$['paths']['/api/v1/agents/{agentName}/skills']['post']";
    String uploadSchema = "$['components']['schemas']['SkillUploadRequest']";

    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(operation + "['requestBody']['required']").value(true))
        .andExpect(
            jsonPath(
                    operation
                        + "['requestBody']['content']['multipart/form-data']['schema']['$ref']")
                .value("#/components/schemas/SkillUploadRequest"))
        .andExpect(jsonPath(uploadSchema + "['required'][0]").value("file"))
        .andExpect(jsonPath(uploadSchema + "['properties']['file']['type']").value("string"))
        .andExpect(jsonPath(uploadSchema + "['properties']['file']['format']").value("binary"))
        .andExpect(
            jsonPath(operation + "['responses']['400']['content']['application/json']['schema']")
                .exists())
        .andExpect(
            jsonPath(operation + "['responses']['404']['content']['application/json']['schema']")
                .exists())
        .andExpect(
            jsonPath(operation + "['responses']['409']['content']['application/json']['schema']")
                .exists())
        .andExpect(
            jsonPath(operation + "['responses']['413']['content']['application/json']['schema']")
                .exists())
        .andExpect(
            jsonPath(operation + "['responses']['500']['content']['application/json']['schema']")
                .exists())
        .andExpect(
            jsonPath(
                    "$['components']['schemas']['SkillSummaryView']['properties']['status']['enum']")
                .value(contains("enabled", "disabled", "invalid")))
        .andExpect(
            jsonPath(
                    "$['components']['schemas']['SkillDetailView']['properties']['status']['enum']")
                .value(contains("enabled", "disabled", "invalid")))
        .andExpect(
            jsonPath(
                    "$['components']['schemas']['SkillSummaryView']['properties']['source']['enum']")
                .value(contains("upload", "workspace")))
        .andExpect(
            jsonPath(
                    "$['components']['schemas']['SkillDetailView']['properties']['source']['enum']")
                .value(contains("upload", "workspace")));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration(
      exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
  @Import(AgentSkillApiController.class)
  static class TestApplication {

    @Bean
    SkillManagementService skillManagementService() {
      return mock(SkillManagementService.class);
    }
  }
}
