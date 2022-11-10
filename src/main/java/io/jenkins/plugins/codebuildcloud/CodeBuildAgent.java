package io.jenkins.plugins.codebuildcloud;

import java.io.IOException;
import java.util.Collections;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;

import com.amazonaws.services.codebuild.model.ResourceNotFoundException;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;

public class CodeBuildAgent extends AbstractCloudSlave {

  final transient CodeBuildCloud cloud;
  private static final Logger LOGGER = Logger.getLogger(CodeBuildAgent.class.getName());
  private static final long serialVersionUID = 1; // SpotBugs

  transient boolean terminated = false;

  public CodeBuildAgent(String name, @NonNull CodeBuildCloud cloud, @NonNull ComputerLauncher launcher)
      throws Descriptor.FormException, IOException {
    super(name,
        "/build",
        launcher);

    this.setNodeDescription("CodeBuild Agent");
    this.setNumExecutors(1);
    this.setMode(Mode.EXCLUSIVE);
    this.setLabelString(cloud.getLabel());

    // This is just in case we dont get a proper exit from the worker.
    // This should match the connect timeout + a little bit. After that - if it is
    // idle we can clean up right away.
    int idleCheck = 2 * 60;
    this.setRetentionStrategy(new CodeBuildRetentionStrategy(idleCheck));

    this.setNodeProperties(Collections.emptyList());
    this.cloud = cloud;

  }

  @Override
  public AbstractCloudComputer<CodeBuildAgent> createComputer() {
    return new CodeBuildComputer(this);
  }

  /** {@inheritDoc} */
  @Override
  protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
    listener.getLogger().println("Terminating agent: " + getDisplayName());
    LOGGER.finest("Terminating agent: " + getDisplayName());

    if (getLauncher() instanceof CodeBuildLauncher) {
      CodeBuildComputer comp = (CodeBuildComputer) getComputer();
      if (comp == null) {
        terminated = true;
        return;
      }

      String buildId = comp.getBuildId();
      if (StringUtils.isBlank(buildId)) {
        terminated = true;
        return;
      }

      LOGGER.finest("Terminating agent Step2: " + getDisplayName());
      try {
        cloud.getClient().stopBuild(buildId);
      } catch (ResourceNotFoundException e) {
        // this is fine. really.
      } catch (Exception e) {
        LOGGER.severe(String.format("Failed to stop build ID: %s.  Exception: %s", buildId, e));
      }
      terminated = true;
    }
  }
}
