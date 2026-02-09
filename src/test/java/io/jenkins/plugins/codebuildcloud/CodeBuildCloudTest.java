package io.jenkins.plugins.codebuildcloud;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;

@WithJenkins
class CodeBuildCloudTest {

  private JenkinsRule j;

  @BeforeEach
  void beforeEach(JenkinsRule rule) {
    j = rule;
  }

  @Test
  void testInitPlugin() {
    final CodeBuildCloud cloud = new CodeBuildCloud("Test1", "hello", null, null, null, null, null, null, null, null,
        null,
        null,
        null,
        null, null, null, null, null, null, null, null, null);
    assertEquals("hello", cloud.getCodeBuildProjectName());
  }

}
