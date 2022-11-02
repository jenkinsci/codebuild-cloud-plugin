package io.jenkins.plugins.codebuildcloud;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class CodeBuildCloudTest {

  @Rule
  public JenkinsRule j = new JenkinsRule();

  @Test
  public void testInitPlugin() throws Exception {
    final CodeBuildCloud cloud = new CodeBuildCloud(null, "hello", null, null, null, null, null, null, null, null, null,
        null,
        null, null, null, null, null, null, null, null, null);
    Assert.assertEquals("hello", cloud.getCodeBuildProjectName());
  }

}
