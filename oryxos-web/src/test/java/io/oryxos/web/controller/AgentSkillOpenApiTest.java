package io.oryxos.web.controller;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.skill.PublicSkillCatalog;
import io.oryxos.core.skill.PublicSkillManagementService;
import io.oryxos.core.skill.SkillAssociationManager;
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

/** Locks the generated SpringDoc contract to the public market and canonical association API. */
@SpringBootTest(classes = AgentSkillOpenApiTest.TestApplication.class)
@AutoConfigureMockMvc
class AgentSkillOpenApiTest {

  @Autowired private MockMvc mvc;

  @Test
  void generatedDocumentPublishesPublicUploadAndCanonicalAssociationOperations() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$['paths']['/api/v1/skills']['get']").exists())
        .andExpect(jsonPath("$['paths']['/api/v1/skills']['post']['requestBody']").exists())
        .andExpect(
            jsonPath(
                    "$['paths']['/api/v1/skills']['post']['requestBody']['content']['multipart/form-data']")
                .exists())
        .andExpect(jsonPath("$['paths']['/api/v1/skills/{name}']['delete']").exists())
        .andExpect(jsonPath("$['paths']['/api/v1/agents/{agentName}/skills']['get']").exists())
        .andExpect(
            jsonPath("$['paths']['/api/v1/agents/{agentName}/skills/{skillName}']['put']").exists())
        .andExpect(
            jsonPath("$['paths']['/api/v1/agents/{agentName}/skills/{skillName}']['delete']")
                .exists());
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration(
      exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
  @Import({SkillApiController.class, AgentSkillApiController.class})
  static class TestApplication {

    @Bean
    PublicSkillManagementService publicSkillManagementService() {
      return mock(PublicSkillManagementService.class);
    }

    @Bean
    SkillAssociationManager skillAssociationManager() {
      return mock(SkillAssociationManager.class);
    }

    @Bean
    PublicSkillCatalog publicSkillCatalog() {
      return mock(PublicSkillCatalog.class);
    }
  }
}
