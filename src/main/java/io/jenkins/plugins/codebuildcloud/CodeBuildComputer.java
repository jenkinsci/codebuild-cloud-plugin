package io.jenkins.plugins.codebuildcloud;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;

public class CodeBuildComputer extends AbstractCloudComputer<CodeBuildAgent> {
  private static final Logger LOGGER = Logger.getLogger(CodeBuildComputer.class.getName());
  private String buildId;

  @NonNull
  private final CodeBuildCloud cloud;

  public CodeBuildComputer(CodeBuildAgent agent) {
    super(agent);
    this.cloud = agent.getCloud();
  }

  /**
   * <p>
   * Getter for the field <code>buildId</code>.
   * </p>
   *
   * @return a {@link String} object.
   */
  public String getBuildId() {
    return buildId;
  }

  /* package */ void setBuildId(String buildId) {
    this.buildId = buildId;
  }

  /**
   * Gets a formatted AWS CodeBuild build URL.
   *
   * @return a {@link String} object.
   */
  public String getBuildUrl() {
    try {
      return String.format("https://%s.console.aws.amazon.com/codesuite/codebuild/projects/%s/build/%s",
          cloud.getRegion(), cloud.getCodeBuildProjectName(), URLEncoder.encode(buildId, "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      return buildId;
    }
  }

  /** {@inheritDoc} */
  @Override
  public void taskAccepted(Executor executor, Queue.Task task) {
    super.taskAccepted(executor, task);
    LOGGER.info(String.format("[%s]: Task in job '%s' accepted", this, task.getFullDisplayName()));
  }

  /** {@inheritDoc} */
  @Override
  public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
    super.taskCompleted(executor, task, durationMS);
    LOGGER.info(String.format("[%s]: Task in job '%s' completed in %sms", this, task.getFullDisplayName(), durationMS));
    gracefulShutdown();
  }

  /** {@inheritDoc} */
  @Override
  public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
    super.taskCompletedWithProblems(executor, task, durationMS, problems);
    LOGGER.severe(String.format("[%s]: Task in job '%s' completed with problems in %sms", this,
        task.getFullDisplayName(), durationMS));
    gracefulShutdown();
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return String.format("name: %s buildID: %s", getName(), getBuildId());
  }

  private void gracefulShutdown() {
    setAcceptingTasks(false);

    Future<Object> next = Computer.threadPoolForRemoting.submit(() -> {
      LOGGER.info(String.format("[%s]: Terminating agent after task.", this));
      try {
        Thread.sleep(500);
        CodeBuildCloud.getJenkins().removeNode(getNode());
      } catch (Exception e) {
        LOGGER.info(String.format("[%s]: Termination error: %s", this, e.getClass()));
      }
      return null;
    });
    next.notify();
  }
}
