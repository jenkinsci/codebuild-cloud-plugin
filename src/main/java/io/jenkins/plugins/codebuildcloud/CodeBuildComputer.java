package io.jenkins.plugins.codebuildcloud;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import hudson.model.Executor;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;

public class CodeBuildComputer extends AbstractCloudComputer<CodeBuildAgent> {
  private static final Logger LOGGER = Logger.getLogger(CodeBuildComputer.class.getName());
  private String buildId;

  @Nonnull
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

  /** {@inheritDoc} */
  @Override
  public void taskAccepted(Executor executor, Queue.Task task) {
    super.taskAccepted(executor, task);
    LOGGER.log(Level.INFO, "[{0}]: JobName: {1}", new Object[] { this.getName(), task.getDisplayName() });
    LOGGER.log(Level.INFO, "[{0}]: JobUrl: {1}", new Object[] { this.getName(), task.getUrl() });
    LOGGER.log(Level.FINE, "[{0}]: taskAccepted", this);
  }

  /** {@inheritDoc} */
  @Override
  public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
    super.taskCompleted(executor, task, durationMS);
    LOGGER.log(Level.FINE, "[{0}]: taskCompleted", this);
  }

  /** {@inheritDoc} */
  @Override
  public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
    super.taskCompletedWithProblems(executor, task, durationMS, problems);
    LOGGER.log(Level.FINE, "[{0}]: taskCompletedWithProblems", this);
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return String.format("name: %s buildID: %S Node: %s", getName(), getBuildId(), getNode());
  }
}
