package io.jenkins.plugins.codebuildcloud;

import java.io.InvalidObjectException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.services.codebuild.AWSCodeBuild;
import com.amazonaws.services.codebuild.AWSCodeBuildClientBuilder;
import com.amazonaws.services.codebuild.model.BatchGetBuildsRequest;
import com.amazonaws.services.codebuild.model.BatchGetBuildsResult;
import com.amazonaws.services.codebuild.model.BatchGetProjectsRequest;
import com.amazonaws.services.codebuild.model.BatchGetProjectsResult;
import com.amazonaws.services.codebuild.model.Build;
import com.amazonaws.services.codebuild.model.ListProjectsRequest;
import com.amazonaws.services.codebuild.model.ListProjectsResult;
import com.amazonaws.services.codebuild.model.Project;
import com.amazonaws.services.codebuild.model.StartBuildRequest;
import com.amazonaws.services.codebuild.model.StartBuildResult;
import com.amazonaws.services.codebuild.model.StopBuildRequest;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsHelper;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ProxyConfiguration;
import jenkins.model.Jenkins;

public class CodeBuildClientWrapper {
  private AWSCodeBuild _client;

  public CodeBuildClientWrapper(String credentialsId, String region, Jenkins instance) {
    this._client = buildClient(credentialsId, region, instance);
  }

  private static final Logger LOGGER = Logger.getLogger(CodeBuildClientWrapper.class.getName());

  private static transient Cache<String, Integer> myCache = Caffeine.newBuilder()
      .expireAfterWrite(1, TimeUnit.HOURS).build();

  private static AWSCodeBuild buildClient(String credentialsId, String region, Jenkins instance) {

    ProxyConfiguration proxy = instance.proxy;
    ClientConfiguration clientConfiguration = new ClientConfiguration();

    if (proxy != null) {
      clientConfiguration.setProxyHost(proxy.name);
      clientConfiguration.setProxyPort(proxy.port);
      clientConfiguration.setProxyUsername(proxy.getUserName());
      clientConfiguration.setProxyPassword(proxy.getPassword());
    }

    AWSCodeBuildClientBuilder builder = AWSCodeBuildClientBuilder.standard()
        .withClientConfiguration(clientConfiguration).withRegion(region);

    AmazonWebServicesCredentials credentials = AWSCredentialsHelper.getCredentials(credentialsId, instance);

    if (credentials != null) {
      String awsAccessKeyId = credentials.getCredentials().getAWSAccessKeyId();
      LOGGER.finest("Using credentials:" + awsAccessKeyId);
      builder.withCredentials(credentials);
    }

    LOGGER.log(Level.FINEST, "Selected Region: " + region);

    return builder.build();
  }

  public ListProjectsResult listProjects(ListProjectsRequest request) {
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

    BatchGetBuildsRequest req = new BatchGetBuildsRequest();
    req.setIds(Arrays.asList(buildId));

    BatchGetBuildsResult res = _client.batchGetBuilds(req);
    assert res.getBuilds().size() == 1;

    Build b = res.getBuilds().get(0);
    String bstatus = b.getBuildStatus();

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

  public StartBuildResult startBuild(StartBuildRequest req) {
    return _client.startBuild(req);
  }

  public void stopBuild(@NonNull String buildId) {

    LOGGER.finest(String.format("Stop Build Requested for build ID: %s", buildId));

    CodeBuildStatus status = getBuildStatus(buildId);

    // No other use cases make sense to stop the build right?
    if (status == CodeBuildStatus.IN_PROGRESS) {
      try {
        LOGGER.finest(String.format("Stopping build ID: %s", buildId));
        _client.stopBuild(new StopBuildRequest().withId(buildId));
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
      BatchGetProjectsResult res = this._client.batchGetProjects(new BatchGetProjectsRequest().withNames(jobName));
      assert res.getProjects().size() == 1;
      Project myproj = res.getProjects().get(0);

      if (myproj.getConcurrentBuildLimit() != null) {
        result = myproj.getConcurrentBuildLimit();
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