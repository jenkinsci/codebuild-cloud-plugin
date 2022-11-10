package io.jenkins.plugins.codebuildcloud;

import java.util.logging.Logger;

import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;

import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.CloudRetentionStrategy;

public class CodeBuildRetentionStrategy extends CloudRetentionStrategy
    implements ExecutorListener {

  private OnceRetentionStrategy realStrat;
  private static final Logger LOGGER = Logger.getLogger(OnceRetentionStrategy.class.getName());

  public CodeBuildRetentionStrategy(int idleMinutes) {
    super(idleMinutes);
    realStrat = new OnceRetentionStrategy(idleMinutes);
  }

  @Override
  public long check(final AbstractCloudComputer c) {

    LOGGER.info("Current Time:" + System.currentTimeMillis());
    LOGGER.info("Idle Time:" + c.getIdleStartMilliseconds());
    long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();

    LOGGER.info("Idle Total Time: " + idleMilliseconds);
    LOGGER.info("Computer Offline: " + c.isOffline());

    return super.check(c);
  }

  @Override
  public void start(AbstractCloudComputer c) {
    realStrat.start(c);
  }

  @Override
  public void taskAccepted(Executor executor, Queue.Task task) {
    realStrat.taskAccepted(executor, task);

  }

  @Override
  public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
    realStrat.taskCompleted(executor, task, durationMS);
  }

  @Override
  public void taskCompletedWithProblems(Executor executor, Queue.Task task,
      long durationMS, Throwable problems) {
    realStrat.taskCompletedWithProblems(executor, task, durationMS, problems);
  }

}
