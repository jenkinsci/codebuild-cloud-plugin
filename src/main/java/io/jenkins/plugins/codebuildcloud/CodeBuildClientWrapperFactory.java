package io.jenkins.plugins.codebuildcloud;

import jenkins.model.Jenkins;

public class CodeBuildClientWrapperFactory {

  public static CodeBuildClientWrapper buildClient(String credentialsId, String region, Jenkins instance) {
    return new CodeBuildClientWrapper(credentialsId, region, instance);
  }
}
