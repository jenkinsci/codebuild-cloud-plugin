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

  public CodeBuildRetentionStrategy() {
    super(1);
    realStrat = new OnceRetentionStrategy(1);
  }

  @Override
  public long check(final AbstractCloudComputer c) {

    // If we get to launched = true - we had an agent connection and now we need to
    // activate the retention strategy. Otherwise we need this disabled
    // So isLaunchSupported == true when launched == False (which means no agent
    // connection yet)
    if (c.isLaunchSupported()) {
      // Let the launcher handle it and dont activate any OnceRetentionStrategies yet.
      LOGGER.info("Retention strategy check disabled - letting launcher handle deletion");
      return 1;
    } else {
      LOGGER.info("Retention strategy check enabled");
      return realStrat.check(c);
    }
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
