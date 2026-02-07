package io.jenkins.plugins.codebuildcloud;

import java.io.InvalidObjectException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.services.codebuild.CodeBuildClient;
import software.amazon.awssdk.services.codebuild.CodeBuildClientBuilder;
import software.amazon.awssdk.services.codebuild.model.*;
import software.amazon.awssdk.regions.Region;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsHelper;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import edu.umd.cs.findbugs.annotations.NonNull;

import jenkins.model.Jenkins;

public class CodeBuildClientWrapper {
  private CodeBuildClient _client;

  public CodeBuildClientWrapper(String credentialsId, String region, Jenkins instance) {
    this._client = buildClient(credentialsId, region, instance);
  }

  private static final Logger LOGGER = Logger.getLogger(CodeBuildClientWrapper.class.getName());

  private static transient Cache<String, Integer> myCache = Caffeine.newBuilder()
      .expireAfterWrite(1, TimeUnit.HOURS).build();

  public static SdkHttpClient buildApacheClientWithJenkinsProxy() {
    hudson.ProxyConfiguration jenkinsProxy = Jenkins.get().proxy;

    if (jenkinsProxy == null) {
      return ApacheHttpClient.builder().build();
    }

    ProxyConfiguration.Builder awsProxyBuilder = ProxyConfiguration.builder()
        .endpoint(
            URI.create("http://" + jenkinsProxy.name + ":" + jenkinsProxy.port));

    // Optional auth
    if (jenkinsProxy.name != null && !jenkinsProxy.name.isEmpty()) {
      awsProxyBuilder.username(jenkinsProxy.name);

      if (jenkinsProxy.getSecretPassword().getPlainText() != null) {
        awsProxyBuilder.password(jenkinsProxy.getSecretPassword().getPlainText());
      }
    }

    // noProxyHost: "localhost|*.example.com"
    if (jenkinsProxy.getNoProxyHost() != null && !jenkinsProxy.getNoProxyHost().isEmpty()) {
      List<String> nonProxyHosts = Arrays.stream(jenkinsProxy.getNoProxyHost().split("\\|"))
          .map(String::trim)
          .collect(Collectors.toList());

      awsProxyBuilder.nonProxyHosts(new HashSet<>(nonProxyHosts));
    }

    return ApacheHttpClient.builder()
        .proxyConfiguration(awsProxyBuilder.build())
        .build();
  }

  private static CodeBuildClient buildClient(String credentialsId, String region, Jenkins instance) {

    SdkHttpClient cli = buildApacheClientWithJenkinsProxy();
    CodeBuildClientBuilder builder = CodeBuildClient.builder().region(Region.of(region)).httpClient(cli);
    AmazonWebServicesCredentials credentials = AWSCredentialsHelper.getCredentials(credentialsId, instance);

    if (credentials != null) {
      String awsAccessKeyId = credentials.getCredentials().getAWSAccessKeyId();
      LOGGER.finest("Using credentials:" + awsAccessKeyId);
      builder.credentialsProvider(credentials);
      // builder.withCredentials(credentials);
    }

    LOGGER.log(Level.FINEST, "Selected Region: " + region);

    return builder.build();
  }

  public ListProjectsResponse listProjects(ListProjectsRequest request) {
    return _client.listProjects(request);
  }

  public enum CodeBuildStatus {
    FAILED,
    FAULT,
    IN_PROGRESS,
    STOPPED,
    SUCCEEDED,
    TIMED_OUT
  }

  public CodeBuildStatus getBuildStatus(@NonNull String buildId) {

    BatchGetBuildsRequest req = BatchGetBuildsRequest.builder().ids(Arrays.asList(buildId)).build();
    // req.setIds(Arrays.asList(buildId));

    BatchGetBuildsResponse res = _client.batchGetBuilds(req);
    assert res.builds().size() == 1;

    Build b = res.builds().get(0);
    String bstatus = b.buildStatus().toString();

    return CodeBuildStatus.valueOf(bstatus);
  }

  public void checkBuildStatus(@NonNull String buildId, List<CodeBuildStatus> invalidStatuses)
      throws InvalidObjectException {

    // Run request to ask Codebuild status of the build. This allows us to fail fast
    // on this side of the connection.
    CodeBuildStatus status = getBuildStatus(buildId);
    LOGGER.finest("Current Build Status: buildId - " + buildId + " Status: " + status.name());

    if (invalidStatuses.contains(status)) {
      throw new InvalidObjectException("Invalid CodeBuild status detected");
    }
  }

  public StartBuildResponse startBuild(StartBuildRequest req) {
    return _client.startBuild(req);
  }

  public void stopBuild(@NonNull String buildId) {

    LOGGER.finest(String.format("Stop Build Requested for build ID: %s", buildId));

    CodeBuildStatus status = getBuildStatus(buildId);

    // No other use cases make sense to stop the build right?
    if (status == CodeBuildStatus.IN_PROGRESS) {
      try {
        LOGGER.finest(String.format("Stopping build ID: %s", buildId));
        _client.stopBuild(StopBuildRequest.builder().id(buildId).build());
        // _client.stopBuild(new StopBuildRequest().withId(buildId));
      } catch (Exception e) {
        LOGGER.severe(String.format("Exception while attempting to stop build: %s.  Exception %s", e.getMessage(), e));
      }
    } else {

      LOGGER.finest(String.format("Build ID: %s already stopped", buildId));
    }
  }

  private Integer _getMaxConcurrentJobs(@NonNull String jobName) {

    Integer result = Integer.MAX_VALUE;

    try {
      BatchGetProjectsResponse res = this._client
          .batchGetProjects(BatchGetProjectsRequest.builder().names(jobName).build());
      assert res.projects().size() == 1;
      Project myproj = res.projects().get(0);

      if (myproj.concurrentBuildLimit() != null) {
        result = myproj.concurrentBuildLimit();
      }
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Unable to determine codebuild project size", e);
    }

    LOGGER.finest("Total possible concurrent jobs  is being set to " + result);
    return result;
  }

  public Integer getMaxConcurrentJobs(@NonNull String jobName) {
    return myCache.get(jobName, j -> _getMaxConcurrentJobs(j));

  }
}